---
name: android-use
description: Control an Android 13+ device via the android-use HTTP API (local adb forward or Tailscale). Use when you need to inspect the Android screen (screenshot or accessibility UI tree), tap, long-press, swipe, type text, press system keys, launch apps, or wait for UI elements on a connected Android phone.
---

# android-use

Operate an Android 13+ device running the `android-use` companion app via its versioned HTTP/JSON API (`/v1/...`) using the bundled helper script `scripts/android-use.sh`.

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
3. **Operate text-first — do NOT take screenshots by default**:
   - Screenshots trigger Android OS rate limits (0.3–1s), image encoding/transfer overhead, and extra vision tokens. Treat screenshots **strictly as a fallback**.
   - **Default fast loop (text-only, ~30ms, no rate limit)**:
     - Run `scripts/android-use.sh marks` to get a compact numbered list of all live interactive elements (`[1] "Search" (editable)`, `[2] "Wi-Fi" (unchecked)`, … plus `unlabeled` count).
     - Run `scripts/android-use.sh tree` when you also need non-interactive screen text (messages, prices, status labels).
     - Tap directly with `tap-mark <id>`, `tap-text "<label>"`, `tap-id "<resourceId>"`, or `tap-node <nodeId>`. (`tap-text` / `tap-id` re-query the **live** UI tree right before tapping and clip bounds to the visible screen.)
   - **Fallback 1 — Set-of-Mark visual tap (`screenshot-marked` + `tap-mark <id>`)**:
     - Use **only** when `marks` shows unlabeled icon buttons you cannot disambiguate by text (`unlabeled > 0`), the layout is visually ambiguous, or repeated actions did not change the UI state.
     - Run `scripts/android-use.sh screenshot-marked /tmp/marked.jpg`, visually pick the badge number, and run `tap-mark <markId>`.
   - **Fallback 2 — Raw coordinate tap (`screenshot` + `tap-image` / `normalized`)**:
     - Use **only** when `marks` / `tree` cannot see the target at all (Canvas, games, maps, custom WebView) or when inspecting visual content (photos, colors, charts).
     - Check the `X-Image-Width` / `X-Image-Height` headers (downscaled to `maxDimension=1280` by default). **Never** pass scaled screenshot pixels into `tap <x> <y> pixel` — always use `tap-image <x> <y> <imageWidth> <imageHeight>` or `tap <x> <y> normalized` (`0.0..1.0`).
4. **Focus before typing**:
   - Always tap an editable field (`editable: true`, via `tap-mark`, `tap-text`, or `tap-node`) before calling `type`, `clear`, or `ime`.
   - If `type` returns `409 NO_FOCUSED_INPUT`, tap the input field again and retry.
5. **Wait on UI transitions**:
   - Prefer `wait-text "<expected>" 5000` or `wait-gone "<spinner>" 5000` over fixed sleeps.
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

# App control & wait helpers (session required)
scripts/android-use.sh apps [query]
scripts/android-use.sh launch <packageName>
scripts/android-use.sh launch-uri <uri>
scripts/android-use.sh wait-sleep [durationMs]
scripts/android-use.sh wait-text <text> [timeoutMs]
scripts/android-use.sh wait-gone <text> [timeoutMs]

# Optional Jev helpers (requires TYPESAFE_API_KEY or OPENROUTER_API_KEY)
scripts/android-use.sh tap-jev "<element to tap on current screen>" [--text "<input>"] [--submit] [--no-vision]
scripts/android-use.sh run-jev "<1-screen micro-goal>" [--max-steps 2] [--text "<input>"] [--submit] [--no-vision]
```

## Optional Jev Helpers (`tap-jev` / `run-jev`)

If `TYPESAFE_API_KEY` (direct TypeSafe `/v1/systemone`, default `jev-latest`) or `OPENROUTER_API_KEY` (OpenRouter `/api/alpha/decisions`, default `typesafe/jev-1.14`) is set in the environment, you can offload fast element selection (~150–300ms) to Jev.

### Division of Responsibility (Preventing Infinite Loops)

**Do NOT hand Jev long multi-step loops.** Because Jev is a stateless System-1 classifier, letting it control scrolling, backtracking, and multi-screen navigation often causes oscillation or infinite loops.

- **Main agent (you) owns the overall flow**: launching apps, deciding which screen to go to, scrolling when an item isn't visible, running `tap-text` when the exact label is already known, and verifying completion.
- **Jev owns 1-shot semantic picking on the current screen (`tap-jev`, recommended)**: pick and tap **one** matching element on the current screen (e.g. *"the first non-ad restaurant card"*, *"the share icon"*, *"the search bar"*) and immediately return `after_screen_marks` so you stay in control with zero loop risk.
- Only use `run-jev` for strictly **1–2 step** micro-transitions (`--max-steps 2`) on a single target screen.

```bash
# Recommended: 1-shot loop-proof pick & tap (returns after_screen_marks)
scripts/android-use.sh tap-jev "Tap the search bar (not 'Ask Maps')" --text "udon" --submit
scripts/android-use.sh tap-jev "Tap the first udon restaurant result (not 'Ask Maps', not sponsored ad)"

# Optional 1-2 step micro-loop (keep max-steps small)
scripts/android-use.sh run-jev "Currently on Google Maps search results for udon. Open the first restaurant detail page. Done when the place name and 'Directions' button are visible. Do not tap 'Ask Maps'." --max-steps 2
```

### Goal Prompting Tips for Jev

1. **Prefer `tap-jev` (1 action)**: Ask Jev only *"pick and tap the 1 element on this screen matching X"*, and handle scrolling / next-step planning yourself.
2. **1 goal = 1 target screen** (if using `run-jev`): Never bundle multiple stages (*"search and open 1st item"*); split into one screen transition per call (`--max-steps 2`).
3. **State explicit negative constraints**: Call out distractors to ignore (*"Do NOT tap 'Ask Maps'"*, *"Do NOT tap sponsored ads"*).
4. **State the current screen & concrete completion markers**: Prefix with *"Currently on …"* and (for `run-jev`) specify visible markers like *"Done when 'Directions' button is visible"*.

- Runs text-first using live `marks` and falls back to `screenshot?annotate=true` only when unlabeled icons or low confidence require vision (disable with `--no-vision`).
- Optional overrides: `JEV_PROVIDER=typesafe|openrouter`, `JEV_MODEL=<model-id>`, `JEV_ENDPOINT=<custom-url>`.
- Exit codes & handoff:
  - `0` (`status: "tapped"` / `"done"`): action succeeded; inspect `after_screen_marks` / `last_screen_marks`.
  - `10` (`status: "not_found"` / `"escalate"` / `"max_steps"`): element absent, low confidence, or cycle detected — scroll or handle directly using returned `screen_marks`.
  - `2` (`code: "JEV_NOT_CONFIGURED"`): no Jev API key is set — continue normally with `marks` / `tree` / `tap-mark`.

## Error Recovery

| HTTP | Error Code | Meaning / Recovery |
| --- | --- | --- |
| `401` | `UNAUTHORIZED` | `ANDROID_USE_TOKEN` is missing or rotated; update token from the app UI. |
| `404` | `ELEMENT_NOT_FOUND` | Selector / `nodeId` / `markId` did not match a visible element; refresh `tree` or `screenshot-marked`, scroll into view, or fall back to `tap-image`. |
| `404` | `APP_NOT_FOUND` | Package is not launchable or URI has no handler; verify with `apps <query>`. |
| `408` | `WAIT_TIMEOUT` | Expected UI element did not appear/disappear in time; inspect `tree` or `screenshot-marked`. |
| `409` | `SESSION_NOT_ACTIVE` | No active session (or session timed out); run `session-start` first. |
| `409` | `NO_FOCUSED_INPUT` | No focused editable field; tap the input box before calling `type`/`clear`/`ime`. |
| `429` | `SCREENSHOT_RATE_LIMITED` | Android throttled consecutive `takeScreenshot()` calls; wait ~500ms or use `allowCachedMs`. |
| `503` | `ACCESSIBILITY_NOT_CONNECTED` | Accessibility Service is disabled on the device; ask the user to enable it in Android Settings. |
