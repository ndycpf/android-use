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
3. **Observe efficiently & pick the right tap method (in priority order)**:
   - **1st choice — Element-targeted tap (highest accuracy, no coordinate drift)**:
     - Run `scripts/android-use.sh tree` first — it is fast and compact.
     - Tap labeled buttons, list items, or fields using `scripts/android-use.sh tap-text "<label>"`, `tap-id "<resourceId>"`, or `tap-node <nodeId>`.
     - `tap-text` and `tap-id` re-query the **live** UI tree right before tapping and clip bounds to the visible screen, preventing misses from layout shifts or coordinate mistakes.
   - **2nd choice — Set-of-Mark visual tap (best for icon-only buttons, dense UIs, or ambiguous labels)**:
     - Run `scripts/android-use.sh screenshot-marked /tmp/marked.jpg` to get a screenshot overlaid with numbered boxes (`[1]`, `[2]`, …) plus a compact marks legend.
     - Visually pick the badge number on the image and tap it directly with `scripts/android-use.sh tap-mark <markId>` — no pixel estimation required.
   - **3rd choice — Raw coordinate tap (fallback for Canvas, games, or custom WebView surfaces absent from the UI tree)**:
     - Run `scripts/android-use.sh screenshot /tmp/screen.jpg` and check the `X-Image-Width` / `X-Image-Height` headers (screenshots are downscaled to `maxDimension=1280` by default).
     - **Never** pass scaled screenshot pixels into `tap <x> <y> pixel`. Always use `tap-image <x> <y> <imageWidth> <imageHeight>` or `tap <x> <y> normalized` (`0.0..1.0`).
4. **Focus before typing**:
   - Always tap an editable field (`editable: true`, via `tap-text`, `tap-mark`, or `tap-node`) before calling `type`, `clear`, or `ime`.
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

# Observation (session required)
scripts/android-use.sh tree [maxDepth] [maxNodes]
scripts/android-use.sh screenshot-marked [out.jpg] [jpeg|png] [maxDimension]
scripts/android-use.sh marks [refresh:true|false]
scripts/android-use.sh screenshot [out.jpg] [jpeg|png] [maxDimension]
scripts/android-use.sh state [includeScreenshot:true|false] [annotate:true|false]

# Element-targeted & Set-of-Mark actions (preferred, session required)
scripts/android-use.sh tap-text <text> [index] [exact:true|false]
scripts/android-use.sh tap-id <resourceId> [index]
scripts/android-use.sh tap-node <nodeId>
scripts/android-use.sh tap-mark <markId>

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
```

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
