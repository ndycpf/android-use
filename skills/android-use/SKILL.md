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

Confirm `"accessibilityConnected": true` before attempting screenshots, UI inspection, or input actions.

## Recommended Agent Workflow

1. **Prefer launching apps directly** instead of swiping through launcher pages:
   - Run `scripts/android-use.sh apps <query>` to find the `packageName`.
   - Run `scripts/android-use.sh launch <packageName>` (or `launch-uri <uri>`).
2. **Observe efficiently**:
   - Run `scripts/android-use.sh tree` first — it is fast, lightweight, and returns exact native pixel `center` coordinates for every interactive element.
   - Run `scripts/android-use.sh screenshot /tmp/screen.jpg` when visual context, icons, images, or WebView layouts are needed.
3. **Act with the right coordinate space**:
   - Coordinates from `tree` (`node.center.x`, `node.center.y`) are native `pixel` coordinates.
   - Coordinates estimated visually from a screenshot should use `normalized` (`0.0..1.0`) or `image` (`imageWidth`, `imageHeight`) so scaling matches the device display automatically.
4. **Focus before typing**:
   - Always `tap` an editable field (`editable: true`) before calling `type`, `clear`, or `ime`.
   - If `type` returns `409 NO_FOCUSED_INPUT`, tap the input field again and retry.
5. **Wait on UI transitions**:
   - After tapping a button or launching an app, prefer `wait-text "<expected>" 5000` or `wait-gone "<spinner>" 5000` over fixed sleeps.
6. **Safety guardrails**:
   - Ask the user for explicit confirmation before irreversible or sensitive actions (sending emails/messages, making payments or purchases, deleting data, modifying account/security settings, or entering passwords/2FA codes).

## Helper Script Reference (`scripts/android-use.sh`)

```bash
# Status & observation
scripts/android-use.sh health
scripts/android-use.sh info
scripts/android-use.sh screenshot [out.jpg] [jpeg|png] [maxDimension]
scripts/android-use.sh tree [maxDepth] [maxNodes]
scripts/android-use.sh state [includeScreenshot:true|false]

# Pointer & navigation actions
scripts/android-use.sh tap <x> <y> [pixel|normalized]
scripts/android-use.sh tap-image <x> <y> <imageWidth> <imageHeight>
scripts/android-use.sh long-press <x> <y> [durationMs]
scripts/android-use.sh swipe [startX startY endX endY pixel|normalized durationMs]
scripts/android-use.sh key <BACK|HOME|RECENTS|NOTIFICATIONS|QUICK_SETTINGS|DISMISS_NOTIFICATION_SHADE>

# Text input & IME
scripts/android-use.sh type <text> [clearFirst:true|false] [submit:true|false]
scripts/android-use.sh clear
scripts/android-use.sh ime [enter]

# App control & wait helpers
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
| `404` | `APP_NOT_FOUND` | Package is not launchable or URI has no handler; verify with `apps <query>`. |
| `408` | `WAIT_TIMEOUT` | Expected UI element did not appear/disappear in time; inspect `tree` or `screenshot`. |
| `409` | `NO_FOCUSED_INPUT` | No focused editable field; `tap` the input box before calling `type`/`clear`/`ime`. |
| `429` | `SCREENSHOT_RATE_LIMITED` | Android throttled consecutive `takeScreenshot()` calls; wait ~500ms or use `allowCachedMs`. |
| `503` | `ACCESSIBILITY_NOT_CONNECTED` | Accessibility Service is disabled on the device; ask the user to enable it in Android Settings. |
