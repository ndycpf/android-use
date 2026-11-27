---
name: android-use
description: Control an Android 13+ device via the android-use HTTP API (local adb forward or Tailscale). Use when you need to inspect the Android screen (screenshot or accessibility UI tree), tap, long-press, swipe, type text, press system keys, launch apps, or wait for UI elements on a connected Android phone.
---

# android-use

Operate an Android 13+ device running the `android-use` companion app via its versioned HTTP/JSON API (`/v1/...`) using the bundled helper script `scripts/android-use.sh`.

## Recommended Model (Codex Subscription)

For Android computer-use tasks, **Codex with `gpt-6.1-terra` (`low` reasoning effort + `fast` mode)** is recommended first whenever a Codex / ChatGPT subscription is available — it provides the best speed, UI/visual accuracy, and plan efficiency for interactive multi-step phone control.

- **Interactive Codex CLI / Desktop app**: select `gpt-6.1-terra`, reasoning effort `low`, and Fast mode (`/fast`), or launch with:
  ```bash
  codex -m gpt-6.1-terra -c 'model_reasoning_effort="low"' -c 'service_tier="fast"'
  ```
- **Non-interactive / delegated run** (if `codex` CLI is installed and signed in with a Codex subscription):
  ```bash
  codex exec -m gpt-6.1-terra -c 'model_reasoning_effort="low"' -c 'service_tier="fast"' "<task using $android-use>"
  ```
- **Fallback**: If a Codex subscription, `codex` CLI, `gpt-6.1-terra`, or Fast mode is not available, simply continue normally with the current model and `scripts/android-use.sh`.

## Connection Setup

Set these environment variables before invoking the helper script:

- `ANDROID_USE_URL`: Base URL of the device server.
  - USB / `adb forward tcp:8765 tcp:8765`: `http://127.0.0.1:8765` (default)
  - Tailscale / LAN: `http://<device-ip>:8765`
- `ANDROID_USE_TOKEN`: Bearer token displayed in the `android-use` app UI. Required whenever connecting outside loopback (`127.0.0.1`).

Verify connectivity first:

```bash
scripts/android-use.sh health
```

Confirm `"accessibilityConnected": true` before starting a session.

## Mandatory Session Lifecycle

All screen capture, UI inspection, app listing/launching, wait, and input endpoints **require an active session** (calling them outside a session returns `409 SESSION_NOT_ACTIVE`).

While a session is active:
- An on-screen visual indicator stays continuously visible on the phone so the user knows an agent is active.
- Display auto-sleep is prevented (`keepAwake: true` by default).
- Idle sessions automatically expire after `idleTimeoutMs` (default 10 minutes).

Always bracket your task with `session-start` and `session-end`:

```bash
scripts/android-use.sh session-start "my-task"
# ... observe and act ...
scripts/android-use.sh session-end
```

Check `screenOn` and `deviceLocked` in the `session-start` / `info` response:
- `session-start` automatically attempts to wake the display (`wakeScreen: true`), or you can call `scripts/android-use.sh wake`.
- If `deviceLocked: true`, non-root Android apps cannot bypass PIN/biometric locks — pause and ask the user to unlock the phone.

## Recommended Agent Workflow

1. **Start a session** (`scripts/android-use.sh session-start "<short-label>"`) and verify `deviceLocked == false`.
2. **Prefer launching apps directly** instead of swiping through launcher pages:
   - Run `scripts/android-use.sh apps <query>` to find the `packageName`.
   - Run `scripts/android-use.sh launch <packageName>` (or `launch-uri <uri>`).
3. **Strict 1–2 step cap across ALL flows (never chain blind actions)**:
   - Execute **at most 1–2 actions** before re-observing the live screen state (`check` / `marks` / `tree` / `tap-jev` response).
   - Never fire 3+ blind taps/swipes/types in one turn — popups, slow loading, focus changes, or unexpected app switches immediately invalidate later steps.
   - The only valid 2-step pair without re-observing is **tap an editable field → `type`** (or `tap-jev ... --text "..." --submit`), and even then re-check right after.
4. **Operate text-first — do NOT take screenshots by default**:
   - Screenshots trigger Android OS rate limits (0.3–1s), image encoding/transfer overhead, and extra vision tokens. Treat screenshots **strictly as a fallback**.
   - **Default fast loop (text-only, ~30–50ms, no rate limit)**:
     - Run `scripts/android-use.sh check` to get `foregroundPackage`, lock state, and a compact numbered list of live interactive elements (`[1] "Search" (editable)`, `[2] "Wi-Fi" (unchecked)`, … plus `unlabeled` count) in one command.
     - Run `scripts/android-use.sh tree` when you also need non-interactive text (messages, prices, dialog body, error banners).
     - Act with `tap-mark <id>`, `tap-text "<label>"`, `tap-id "<resourceId>"`, `tap-node <nodeId>`, or optional 1-shot `tap-jev` (when a semantic description is cleaner than raw labels).
   - **Fallback 1 — Set-of-Mark visual tap (`screenshot-marked` + `tap-mark <id>`)**:
     - Use **only** when `check`/`marks` shows unlabeled icon buttons you cannot disambiguate (`unlabeled > 0`), the layout is visually ambiguous, or a text-only tap failed/produced `screen_changed: false`.
     - Run `scripts/android-use.sh screenshot-marked /tmp/marked.jpg`, visually pick the badge number, and run `tap-mark <markId>`.
   - **Fallback 2 — Raw coordinate tap (`screenshot` + `tap-image` / `normalized`)**:
     - Use **only** when `marks`/`tree` cannot see the target at all (Canvas, games, maps, custom WebView) or when inspecting visual content (photos, colors, charts).
     - Check the `X-Image-Width` / `X-Image-Height` headers (downscaled to `maxDimension=1280` by default). **Never** pass scaled screenshot pixels into `tap <x> <y> pixel` — always use `tap-image <x> <y> <imageWidth> <imageHeight>` or `tap <x> <y> normalized` (`0.0..1.0`).
5. **Smart transition waiting & recovery (never rely on fixed sleeps)**:
   - After an action that triggers a screen transition, call `scripts/android-use.sh wait-change [timeoutMs]` (default `3000ms`, `quietPeriodMs=150ms`) — it wakes immediately on Android AccessibilityEvents as soon as the new screen settles and returns `changed`, `elapsed`, `pkg`, and fresh compact `marks` in **one** call.
   - After `launch <packageName>`, call `scripts/android-use.sh wait-package <packageName> 5000` instead of `wait-sleep`.
   - `tap-jev` automatically uses server-side `waitForChange` and returns `screen_changed`, `after_foreground_package`, and `after_screen_marks`.
   - **Recovery Ladder** (when an action fails, hits the wrong screen, or `screen_changed: false` / `changed=false`):
     1. **Wait for event/condition**: Run `wait-change 2500` or `wait-text "<expected>" 3000` (avoid blind `wait-sleep` unless explicitly needed) — animations or network loads will wake immediately once ready.
     2. **Inspect full `tree` & clear blockers**: Read `tree` for unexpected dialogs, permission prompts, snackbars, or focus errors (`409 NO_FOCUSED_INPUT`). Dismiss stray popups (`key BACK` or tap dismiss), or `swipe` once if the target is off-screen.
     3. **Escalate to visual Set-of-Mark**: If a text tap or `tap-jev` misses or produces `screen_changed: false` once after settling, switch immediately to `screenshot-marked /tmp/marked.jpg` → `tap-mark <id>` instead of repeating the same failing command.
     4. **Coordinate fallback or pause & report**: If the same step fails twice even with visual fallback (e.g. Canvas/WebView or blocked system UI), try `screenshot` + `tap-image` once, or stop and report the exact stuck state (`foregroundPackage` + visible marks) to the user. Never loop endlessly.
6. **End the session when done** (`scripts/android-use.sh session-end`) so the overlay disappears and normal screen sleep resumes.
7. **Safety guardrails**:
   - Ask the user for explicit confirmation before irreversible or sensitive actions (sending emails/messages, making payments or purchases, deleting data, modifying account/security settings, or entering passwords/2FA codes).

## Helper Script Reference (`scripts/android-use.sh`)

```bash
# Health, power & session lifecycle
scripts/android-use.sh health
scripts/android-use.sh info
scripts/android-use.sh wake
scripts/android-use.sh session
scripts/android-use.sh session-start [label] [idleTimeoutMs]
scripts/android-use.sh session-end

# Observation — text-first (default, fast, no rate limit)
scripts/android-use.sh check
scripts/android-use.sh marks [refresh:true|false] [compact|json]
scripts/android-use.sh tree [maxDepth] [maxNodes]
scripts/android-use.sh state [includeScreenshot:true|false] [annotate:true|false]

# Observation — screenshot fallbacks (only when text/marks are insufficient)
scripts/android-use.sh screenshot-marked [out.jpg] [jpeg|png] [maxDimension]
scripts/android-use.sh screenshot [out.jpg] [jpeg|png] [maxDimension]

# Element-targeted & Set-of-Mark actions (preferred, session required)
scripts/android-use.sh tap-mark <markId>
scripts/android-use.sh tap-text <text> [index] [exact:true|false]
scripts/android-use.sh tap-id <resourceId> [index]
scripts/android-use.sh tap-node <nodeId>
scripts/android-use.sh tap-jev "<1 element on current screen>" [--text "<input>"] [--submit] [--no-vision]

# Raw coordinate & navigation actions (fallback / gestures, session required)
scripts/android-use.sh tap <x> <y> [pixel|normalized]
scripts/android-use.sh tap-image <x> <y> <imageWidth> <imageHeight>
scripts/android-use.sh long-press <x> <y> [durationMs]
scripts/android-use.sh swipe [startX startY endX endY pixel|normalized durationMs]
scripts/android-use.sh key <BACK|HOME|RECENTS|NOTIFICATIONS|QUICK_SETTINGS|DISMISS_NOTIFICATION_SHADE>

# Text input & IME (session required)
scripts/android-use.sh type <text> [clearFirst:true|false] [submit:true|false]
scripts/android-use.sh clear
scripts/android-use.sh ime [enter]

# App control & event-driven wait helpers (session required)
scripts/android-use.sh apps [query]
scripts/android-use.sh launch <packageName>
scripts/android-use.sh launch-uri <uri>
scripts/android-use.sh wait-change [timeoutMs] [quietPeriodMs]
scripts/android-use.sh wait-idle [timeoutMs] [quietPeriodMs]
scripts/android-use.sh wait-package <packageName> [timeoutMs]
scripts/android-use.sh wait-text <text> [timeoutMs]
scripts/android-use.sh wait-gone <text> [timeoutMs]
scripts/android-use.sh wait-sleep [durationMs]
```

## Optional 1-Shot Jev Tap (`tap-jev`)

If `TYPESAFE_API_KEY` (direct TypeSafe `/v1/systemone`, default `jev-latest`) or `OPENROUTER_API_KEY` (OpenRouter `/api/alpha/decisions`, default `typesafe/jev-1.14`) is set, you can use `tap-jev` for **strictly 1-shot semantic element picking** on the current screen (~150–300ms, zero loop risk).

- **Main agent (you) owns the entire workflow**: launching apps, scrolling, verifying screen transitions, deciding completion, and running recovery.
- **Jev ONLY picks and taps 1 element on the current screen** (optionally typing pre-supplied `--text` into that tapped field) and returns `screen_changed`, `after_foreground_package`, `after_screen_marks`, and `recovery_hint`.

```bash
scripts/android-use.sh tap-jev "Search bar on Google Maps home (do not tap 'Ask Maps')" --text "udon" --submit
scripts/android-use.sh tap-jev "First udon restaurant result on search list (do not tap 'Ask Maps' or sponsored ads)"
```

- **Prompting tips**: State the current screen + exact target + explicit negative constraints (*"do not tap 'Ask Maps'"*).
- Runs text-first using live `marks` and falls back to `screenshot?annotate=true` only when unlabeled icons or low confidence require vision (disable with `--no-vision`).
- Optional overrides: `JEV_PROVIDER=typesafe|openrouter`, `JEV_MODEL=<model-id>`, `JEV_ENDPOINT=<custom-url>`.
- Exit codes:
  - `0` (`status: "tapped"` / `"dry_run"`): check `screen_changed` and `after_screen_marks`; if `screen_changed == false`, follow the Recovery Ladder.
  - `10` (`status: "not_found"` / `"escalate"`): element absent or low confidence — inspect returned `screen_marks`, scroll, or use `screenshot-marked`.
  - `2` (`code: "JEV_NOT_CONFIGURED"`): no Jev API key set — continue normally with `check` / `tap-mark` / `tap-text`.

## Error Recovery

| HTTP | Error Code | Meaning / Recovery |
| --- | --- | --- |
| `401` | `UNAUTHORIZED` | `ANDROID_USE_TOKEN` is missing or rotated; update token from the app UI. |
| `404` | `ELEMENT_NOT_FOUND` | Selector / `nodeId` / `markId` did not match a visible element; run `check` or `tree`, scroll once, or switch to `screenshot-marked`. |
| `404` | `APP_NOT_FOUND` | Package is not launchable or URI has no handler; verify with `apps <query>`. |
| `408` | `WAIT_TIMEOUT` | Expected UI element did not appear/disappear in time; run `check`/`tree` or `screenshot-marked` to inspect actual state. |
| `409` | `SESSION_NOT_ACTIVE` | No active session (or session timed out); run `session-start` first. |
| `409` | `NO_FOCUSED_INPUT` | No focused editable field; tap the editable field first, verify focus/keyboard, then retry `type`/`clear`/`ime`. |
| `429` | `SCREENSHOT_RATE_LIMITED` | Android throttled consecutive `takeScreenshot()` calls; prefer `check`/`marks`, wait ~500ms, or use `allowCachedMs`. |
| `503` | `ACCESSIBILITY_NOT_CONNECTED` | Accessibility Service is disabled on the device; ask the user to enable it in Android Settings. |
