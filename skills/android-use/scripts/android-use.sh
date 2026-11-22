#!/usr/bin/env bash
set -euo pipefail

URL="${ANDROID_USE_URL:-${URL:-http://127.0.0.1:8765}}"
TOKEN="${ANDROID_USE_TOKEN:-${TOKEN:-}}"

AUTH_ARGS=()
if [[ -n "$TOKEN" ]]; then
  AUTH_ARGS=(-H "Authorization: Bearer ${TOKEN}")
fi

pp() {
  if command -v jq >/dev/null 2>&1; then
    jq . 2>/dev/null || cat
  else
    cat
    echo
  fi
}

request() {
  local method="$1"
  local path="$2"
  local body="${3:-}"
  local tmp
  tmp="$(mktemp)"
  local code

  if [[ -n "$body" ]]; then
    code="$(curl -sS -o "$tmp" -w '%{http_code}' -X "$method" "${AUTH_ARGS[@]}" \
      -H "Content-Type: application/json" \
      -d "$body" \
      "${URL}${path}")"
  else
    code="$(curl -sS -o "$tmp" -w '%{http_code}' -X "$method" "${AUTH_ARGS[@]}" \
      "${URL}${path}")"
  fi

  pp < "$tmp"
  rm -f "$tmp"

  if (( code < 200 || code >= 300 )); then
    echo "HTTP ${code} (${method} ${path})" >&2
    return 1
  fi
}

json_escape() {
  local s="$1"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  s="${s//$'\n'/\\n}"
  s="${s//$'\r'/\\r}"
  s="${s//$'\t'/\\t}"
  printf '%s' "$s"
}

usage() {
  cat <<EOF
Usage:
  ANDROID_USE_URL=http://<host>:8765 ANDROID_USE_TOKEN=<token> $(basename "$0") <command> [args...]

Commands:
  health                                         Check server, accessibility, and session status
  info                                           Get display resolution, power/lock state, and foreground app
  wake                                           Attempt to wake display from sleep
  session                                        Get active session status
  session-start [label] [idleTimeoutMs]          Start a session (keeps overlay & screen awake)
  session-end                                    End active session (clears overlay & restores sleep)
  screenshot [out.jpg] [jpeg|png] [maxDimension] Capture binary screenshot to file (session required)
  screenshot-marked [out.jpg] [jpeg|png] [maxDim] Fallback: Set-of-Mark numbered screenshot + marks legend
  check                                          Get foreground package + lock state + live compact marks (fast)
  marks [refresh:true|false] [compact|json]      Get live interactive elements list (default: true compact, fast)
  tree [maxDepth] [maxNodes]                     Get normalized UI accessibility tree (session required)
  state [includeScreenshot:true|false] [annotate] Get combined device info, UI tree, optional screenshot/marks
  tap-text <text> [index] [exact:true|false]     Tap element by live text/description match (preferred)
  tap-id <resourceId> [index]                    Tap element by live resourceId match
  tap-node <nodeId>                              Tap element by nodeId from latest tree (e.g. n12)
  tap-mark <markId>                              Tap numbered element from latest marks / screenshot-marked
  tap-jev <description> [flags...]               Optional 1-shot Jev semantic tap (needs TYPESAFE_API_KEY or OPENROUTER_API_KEY)
  tap <x> <y> [pixel|normalized]                 Tap screen coordinate (fallback for canvas/custom UI)
  tap-image <x> <y> <imageWidth> <imageHeight>   Tap coordinate in screenshot image space
  long-press <x> <y> [durationMs]                Long-press coordinate
  swipe [sx sy ex ey pixel|normalized durMs]     Swipe (default: scroll up 0.5,0.8 -> 0.5,0.2 normalized)
  key <BACK|HOME|RECENTS|NOTIFICATIONS|...>      Press global navigation key
  type <text> [clearFirst] [submit]              Type text into focused field
  clear                                          Clear focused text field
  ime [enter]                                    Trigger IME action
  apps [query]                                   List launchable apps
  launch <packageName>                           Launch app by package name
  launch-uri <uri>                               Open URI or deep link
  wait-change [timeoutMs] [quietPeriodMs]        Smart event-driven wait for screen switch & settle + print marks
  wait-idle [timeoutMs] [quietPeriodMs]          Wait until UI events settle & print marks
  wait-package <packageName> [timeoutMs]         Wait for foreground app switch & print marks
  wait-text <text> [timeoutMs]                   Wait until text appears (event-driven)
  wait-gone <text> [timeoutMs]                   Wait until text disappears (event-driven)
  wait-sleep [durationMs]                        Fixed sleep wait (use wait-change/wait-text instead)
EOF
}

print_marks_compact() {
  jq -r '
    ([.marks[] | select((.text // "") == "" and (.contentDescription // "") == "" and (.hintText // "") == "")] | length) as $unlabeled |
    "marks: \(.count) (unlabeled: \($unlabeled), display: \(.displayWidth)x\(.displayHeight))",
    (.marks[] |
      "[\(.markId)] " +
      ((.text // .contentDescription // .hintText // .resourceId // .className // "(unlabeled)") | tostring) +
      (if .editable then " (editable)"
       elif .checkable then (if .checked then " (checked)" else " (unchecked)" end)
       else "" end) +
      " @(\(.center.x),\(.center.y))"
    )
  '
}

print_wait_marks_compact() {
  jq -r '
    (.marks // []) as $m |
    ([$m[] | select((.text // "") == "" and (.contentDescription // "") == "" and (.hintText // "") == "")] | length) as $unlabeled |
    "matched=\(.matched) changed=\(.screenChanged // .matched) elapsed=\(.elapsedMs)ms pkg=\(.foregroundPackage // "unknown") hash=\(.screenHash // "-") marks=\($m | length) (unlabeled: \($unlabeled))",
    ($m[] |
      "[\(.markId)] " +
      ((.text // .contentDescription // .hintText // .resourceId // .className // "(unlabeled)") | tostring) +
      (if .editable then " (editable)"
       elif .checkable then (if .checked then " (checked)" else " (unchecked)" end)
       else "" end) +
      " @(\(.center.x),\(.center.y))"
    )
  '
}

cmd="${1:-}"
if [[ -z "$cmd" || "$cmd" == "-h" || "$cmd" == "--help" ]]; then
  usage
  exit 0
fi
shift

case "$cmd" in
  health)
    request GET "/v1/health"
    ;;

  info)
    request GET "/v1/device/info"
    ;;

  wake)
    request POST "/v1/device/wake"
    ;;

  session)
    request GET "/v1/session"
    ;;

  session-start)
    label="${1:-}"
    timeout="${2:-600000}"
    if [[ -n "$label" ]]; then
      if command -v jq >/dev/null 2>&1; then
        payload="$(jq -n --arg l "$label" --argjson to "$timeout" '{label:$l, idleTimeoutMs:$to}')"
      else
        payload="{\"label\":\"$(json_escape "$label")\",\"idleTimeoutMs\":${timeout}}"
      fi
    else
      payload="{\"idleTimeoutMs\":${timeout}}"
    fi
    request POST "/v1/session/start" "$payload"
    ;;

  session-end)
    request POST "/v1/session/end"
    ;;

  screenshot)
    out="${1:-screen.jpg}"
    fmt="${2:-jpeg}"
    max_dim="${3:-1280}"
    tmp_hdr="$(mktemp)"
    code="$(curl -sS -D "$tmp_hdr" -o "$out" -w '%{http_code}' "${AUTH_ARGS[@]}" \
      "${URL}/v1/screen/screenshot?encoding=binary&format=${fmt}&maxDimension=${max_dim}")"
    if (( code < 200 || code >= 300 )); then
      cat "$out" >&2 || true
      rm -f "$tmp_hdr"
      echo "HTTP ${code} (GET /v1/screen/screenshot)" >&2
      exit 1
    fi
    grep -iE '^(Content-Type:|X-)' "$tmp_hdr" || true
    rm -f "$tmp_hdr"
    ls -lh "$out"
    ;;

  screenshot-marked)
    out="${1:-screen_marked.jpg}"
    fmt="${2:-jpeg}"
    max_dim="${3:-1280}"
    tmp_hdr="$(mktemp)"
    code="$(curl -sS -D "$tmp_hdr" -o "$out" -w '%{http_code}' "${AUTH_ARGS[@]}" \
      "${URL}/v1/screen/screenshot?encoding=binary&format=${fmt}&maxDimension=${max_dim}&annotate=true")"
    if (( code < 200 || code >= 300 )); then
      cat "$out" >&2 || true
      rm -f "$tmp_hdr"
      echo "HTTP ${code} (GET /v1/screen/screenshot?annotate=true)" >&2
      exit 1
    fi
    grep -iE '^(Content-Type:|X-)' "$tmp_hdr" || true
    rm -f "$tmp_hdr"
    ls -lh "$out"
    # Print compact marks legend for immediate use with tap-mark <id>.
    if command -v jq >/dev/null 2>&1; then
      curl -sS "${AUTH_ARGS[@]}" "${URL}/v1/screen/marks" | print_marks_compact || true
    else
      request GET "/v1/screen/marks" || true
    fi
    ;;

  check)
    tmp_info="$(mktemp)"
    code_info="$(curl -sS -o "$tmp_info" -w '%{http_code}' "${AUTH_ARGS[@]}" "${URL}/v1/device/info")"
    if (( code_info < 200 || code_info >= 300 )); then
      pp < "$tmp_info"
      rm -f "$tmp_info"
      echo "HTTP ${code_info} (GET /v1/device/info)" >&2
      exit 1
    fi
    if command -v jq >/dev/null 2>&1; then
      jq -r '"pkg=\(.foregroundPackage // "unknown") locked=\(.deviceLocked) screenOn=\(.screenOn)"' < "$tmp_info"
    else
      cat "$tmp_info"
      echo
    fi
    rm -f "$tmp_info"

    tmp_marks="$(mktemp)"
    code_marks="$(curl -sS -o "$tmp_marks" -w '%{http_code}' "${AUTH_ARGS[@]}" "${URL}/v1/screen/marks?refresh=true")"
    if (( code_marks < 200 || code_marks >= 300 )); then
      pp < "$tmp_marks"
      rm -f "$tmp_marks"
      echo "HTTP ${code_marks} (GET /v1/screen/marks?refresh=true)" >&2
      exit 1
    fi
    if command -v jq >/dev/null 2>&1; then
      print_marks_compact < "$tmp_marks"
    else
      pp < "$tmp_marks"
    fi
    rm -f "$tmp_marks"
    ;;

  marks)
    refresh="${1:-true}"
    mode="${2:-compact}"
    if [[ "$mode" == "compact" ]] && command -v jq >/dev/null 2>&1; then
      tmp="$(mktemp)"
      code="$(curl -sS -o "$tmp" -w '%{http_code}' "${AUTH_ARGS[@]}" \
        "${URL}/v1/screen/marks?refresh=${refresh}")"
      if (( code < 200 || code >= 300 )); then
        pp < "$tmp"
        rm -f "$tmp"
        echo "HTTP ${code} (GET /v1/screen/marks?refresh=${refresh})" >&2
        exit 1
      fi
      print_marks_compact < "$tmp"
      rm -f "$tmp"
    else
      request GET "/v1/screen/marks?refresh=${refresh}"
    fi
    ;;

  tree)
    max_depth="${1:-30}"
    max_nodes="${2:-400}"
    request GET "/v1/screen/tree?maxDepth=${max_depth}&maxNodes=${max_nodes}"
    ;;

  state)
    inc="${1:-false}"
    ann="${2:-false}"
    request GET "/v1/screen/state?includeScreenshot=${inc}&annotate=${ann}"
    ;;

  tap-text)
    text="${1:?usage: tap-text <text> [index] [exact:true|false]}"
    idx="${2:-0}"
    exact="${3:-false}"
    if command -v jq >/dev/null 2>&1; then
      payload="$(jq -n --arg t "$text" --argjson i "$idx" --argjson e "$exact" \
        '{text:$t, index:$i, exact:$e}')"
    else
      payload="{\"text\":\"$(json_escape "$text")\",\"index\":${idx},\"exact\":${exact}}"
    fi
    request POST "/v1/actions/tap-element" "$payload"
    ;;

  tap-id)
    res_id="${1:?usage: tap-id <resourceId> [index]}"
    idx="${2:-0}"
    if command -v jq >/dev/null 2>&1; then
      payload="$(jq -n --arg r "$res_id" --argjson i "$idx" '{resourceId:$r, index:$i}')"
    else
      payload="{\"resourceId\":\"$(json_escape "$res_id")\",\"index\":${idx}}"
    fi
    request POST "/v1/actions/tap-element" "$payload"
    ;;

  tap-node)
    node_id="${1:?usage: tap-node <nodeId>}"
    if command -v jq >/dev/null 2>&1; then
      payload="$(jq -n --arg n "$node_id" '{nodeId:$n}')"
    else
      payload="{\"nodeId\":\"$(json_escape "$node_id")\"}"
    fi
    request POST "/v1/actions/tap-element" "$payload"
    ;;

  tap-mark)
    mark_id="${1:?usage: tap-mark <markId>}"
    request POST "/v1/actions/tap-element" "{\"markId\":${mark_id}}"
    ;;

  tap)
    x="${1:?usage: tap <x> <y> [pixel|normalized]}"
    y="${2:?usage: tap <x> <y> [pixel|normalized]}"
    space="${3:-pixel}"
    request POST "/v1/actions/tap" "{\"x\":${x},\"y\":${y},\"coordinateSpace\":\"${space}\"}"
    ;;

  tap-image)
    x="${1:?usage: tap-image <x> <y> <imageWidth> <imageHeight>}"
    y="${2:?usage: tap-image <x> <y> <imageWidth> <imageHeight>}"
    img_w="${3:?usage: tap-image <x> <y> <imageWidth> <imageHeight>}"
    img_h="${4:?usage: tap-image <x> <y> <imageWidth> <imageHeight>}"
    request POST "/v1/actions/tap" \
      "{\"x\":${x},\"y\":${y},\"coordinateSpace\":\"image\",\"imageWidth\":${img_w},\"imageHeight\":${img_h}}"
    ;;

  long-press)
    x="${1:?usage: long-press <x> <y> [durationMs]}"
    y="${2:?usage: long-press <x> <y> [durationMs]}"
    dur="${3:-600}"
    request POST "/v1/actions/long-press" "{\"x\":${x},\"y\":${y},\"durationMs\":${dur}}"
    ;;

  swipe)
    sx="${1:-0.5}"
    sy="${2:-0.8}"
    ex="${3:-0.5}"
    ey="${4:-0.2}"
    space="${5:-normalized}"
    dur="${6:-300}"
    request POST "/v1/actions/swipe" \
      "{\"startX\":${sx},\"startY\":${sy},\"endX\":${ex},\"endY\":${ey},\"coordinateSpace\":\"${space}\",\"durationMs\":${dur}}"
    ;;

  key)
    k="${1:?usage: key <BACK|HOME|RECENTS|NOTIFICATIONS|QUICK_SETTINGS|DISMISS_NOTIFICATION_SHADE>}"
    request POST "/v1/actions/key" "{\"key\":\"${k}\"}"
    ;;

  type)
    text="${1:?usage: type <text> [clearFirst:true|false] [submit:true|false]}"
    clear_first="${2:-false}"
    submit="${3:-false}"
    if command -v jq >/dev/null 2>&1; then
      payload="$(jq -n --arg t "$text" --argjson c "$clear_first" --argjson s "$submit" \
        '{text:$t, clearFirst:$c, submit:$s}')"
    else
      payload="{\"text\":\"$(json_escape "$text")\",\"clearFirst\":${clear_first},\"submit\":${submit}}"
    fi
    request POST "/v1/actions/type" "$payload"
    ;;

  clear)
    request POST "/v1/actions/clear"
    ;;

  ime)
    action="${1:-enter}"
    request POST "/v1/actions/ime" "{\"action\":\"$(json_escape "$action")\"}"
    ;;

  apps)
    q="${1:-}"
    tmp="$(mktemp)"
    if [[ -n "$q" ]]; then
      code="$(curl -sS -G -o "$tmp" -w '%{http_code}' "${AUTH_ARGS[@]}" \
        --data-urlencode "q=${q}" "${URL}/v1/apps")"
    else
      code="$(curl -sS -o "$tmp" -w '%{http_code}' "${AUTH_ARGS[@]}" "${URL}/v1/apps")"
    fi
    pp < "$tmp"
    rm -f "$tmp"
    if (( code < 200 || code >= 300 )); then
      echo "HTTP ${code} (GET /v1/apps)" >&2
      exit 1
    fi
    ;;

  launch)
    pkg="${1:?usage: launch <packageName>}"
    request POST "/v1/apps/launch" "{\"packageName\":\"$(json_escape "$pkg")\"}"
    ;;

  launch-uri)
    uri="${1:?usage: launch-uri <uri>}"
    request POST "/v1/apps/launch" "{\"uri\":\"$(json_escape "$uri")\"}"
    ;;

  wait-change)
    timeout="${1:-3000}"
    quiet="${2:-150}"
    tmp="$(mktemp)"
    code="$(curl -sS -o "$tmp" -w '%{http_code}' -X POST "${AUTH_ARGS[@]}" \
      -H "Content-Type: application/json" \
      -d "{\"waitForChange\":true,\"timeoutMs\":${timeout},\"quietPeriodMs\":${quiet},\"includeMarks\":true,\"failOnTimeout\":false}" \
      "${URL}/v1/wait")"
    if (( code < 200 || code >= 300 )); then
      pp < "$tmp"
      rm -f "$tmp"
      echo "HTTP ${code} (POST /v1/wait waitForChange)" >&2
      exit 1
    fi
    if command -v jq >/dev/null 2>&1; then
      print_wait_marks_compact < "$tmp"
      changed="$(jq -r '.screenChanged // .matched // false' < "$tmp")"
      rm -f "$tmp"
      [[ "$changed" == "true" ]] || exit 10
    else
      pp < "$tmp"
      rm -f "$tmp"
    fi
    ;;

  wait-idle)
    timeout="${1:-3000}"
    quiet="${2:-150}"
    tmp="$(mktemp)"
    code="$(curl -sS -o "$tmp" -w '%{http_code}' -X POST "${AUTH_ARGS[@]}" \
      -H "Content-Type: application/json" \
      -d "{\"waitForIdle\":true,\"timeoutMs\":${timeout},\"quietPeriodMs\":${quiet},\"includeMarks\":true,\"failOnTimeout\":false}" \
      "${URL}/v1/wait")"
    if (( code < 200 || code >= 300 )); then
      pp < "$tmp"
      rm -f "$tmp"
      echo "HTTP ${code} (POST /v1/wait waitForIdle)" >&2
      exit 1
    fi
    if command -v jq >/dev/null 2>&1; then
      print_wait_marks_compact < "$tmp"
      rm -f "$tmp"
    else
      pp < "$tmp"
      rm -f "$tmp"
    fi
    ;;

  wait-package)
    pkg="${1:?usage: wait-package <packageName> [timeoutMs] [quietPeriodMs]}"
    timeout="${2:-5000}"
    quiet="${3:-150}"
    if command -v jq >/dev/null 2>&1; then
      payload="$(jq -n --arg p "$pkg" --argjson to "$timeout" --argjson q "$quiet" \
        '{packageName:$p, timeoutMs:$to, quietPeriodMs:$q, includeMarks:true}')"
    else
      payload="{\"packageName\":\"$(json_escape "$pkg")\",\"timeoutMs\":${timeout},\"quietPeriodMs\":${quiet},\"includeMarks\":true}"
    fi
    tmp="$(mktemp)"
    code="$(curl -sS -o "$tmp" -w '%{http_code}' -X POST "${AUTH_ARGS[@]}" \
      -H "Content-Type: application/json" \
      -d "$payload" \
      "${URL}/v1/wait")"
    if (( code < 200 || code >= 300 )); then
      pp < "$tmp"
      rm -f "$tmp"
      echo "HTTP ${code} (POST /v1/wait packageName=${pkg})" >&2
      exit 1
    fi
    if command -v jq >/dev/null 2>&1; then
      print_wait_marks_compact < "$tmp"
      rm -f "$tmp"
    else
      pp < "$tmp"
      rm -f "$tmp"
    fi
    ;;

  wait-sleep)
    ms="${1:-1000}"
    request POST "/v1/wait" "{\"durationMs\":${ms}}"
    ;;

  wait-text)
    text="${1:?usage: wait-text <text> [timeoutMs]}"
    timeout="${2:-5000}"
    if command -v jq >/dev/null 2>&1; then
      payload="$(jq -n --arg t "$text" --argjson to "$timeout" '{text:$t, timeoutMs:$to}')"
    else
      payload="{\"text\":\"$(json_escape "$text")\",\"timeoutMs\":${timeout}}"
    fi
    request POST "/v1/wait" "$payload"
    ;;

  wait-gone)
    text="${1:?usage: wait-gone <text> [timeoutMs]}"
    timeout="${2:-5000}"
    if command -v jq >/dev/null 2>&1; then
      payload="$(jq -n --arg t "$text" --argjson to "$timeout" '{text:$t, gone:true, timeoutMs:$to}')"
    else
      payload="{\"text\":\"$(json_escape "$text")\",\"gone\":true,\"timeoutMs\":${timeout}}"
    fi
    request POST "/v1/wait" "$payload"
    ;;

  tap-jev)
    script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    exec python3 "${script_dir}/jev-runner.py" "$@"
    ;;

  *)
    echo "Unknown command: $cmd" >&2
    usage >&2
    exit 1
    ;;
esac
