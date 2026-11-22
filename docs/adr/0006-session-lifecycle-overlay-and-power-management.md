# ADR-0006: Explicit Session Lifecycle, Persistent Overlay, and Power Management

- **Status**: Accepted
- **Date**: 2026-11-22

## Context

Real-device testing of Phases 0–6 revealed three practical operational needs when an AI agent drives an Android phone over Tailscale:

1. **Clear visual accountability**: Rather than leaving a visual indicator toggle permanently on or flashing only briefly per tap, users want an explicit **session** boundary (`start` -> `end`) during which an on-screen indicator stays continuously visible so the human always knows an AI agent currently holds control—and disappears cleanly when the session ends.
2. **Preventing auto-sleep mid-task**: Long multi-step LLM reasoning turns can exceed Android's default screen timeout (e.g., 30s–2m), putting the phone to sleep mid-session and stalling automation.
3. **Waking from sleep & lock awareness**: Agents need to know whether the screen is off or locked (`screenOn`, `deviceLocked`) and be able to request a best-effort screen wake when appropriate, while respecting Android's non-root security boundary (PIN/biometric lockscreens cannot be bypassed without root).

## Decision

### 1. Mandatory Session Lifecycle for Observation & Control
Introduce an explicit session lifecycle managed by a transport-agnostic `SessionManager` in `core/`:

- `POST /v1/session/start` — starts an active session (`label`, `keepAwake = true`, `wakeScreen = true`, `idleTimeoutMs = 600_000` [10 minutes default, valid `10_000..3_600_000`]).
- `POST /v1/session/end` — ends the active session immediately.
- `GET /v1/session` — returns current session state plus `screenOn` and `deviceLocked`.
- `POST /v1/device/wake` — attempts to wake the display and returns updated `screenOn` / `deviceLocked`.

**Enforcement**:
- All screen capture, UI tree inspection, pointer/key/text actions, app listing/launching, and wait endpoints **require an active session**.
- Calling any of those endpoints without an active session fails with HTTP `409 Conflict` and `ErrorCode.SESSION_NOT_ACTIVE`.
- Every successful session-scoped call refreshes `lastActivityMs`. If no activity occurs for `idleTimeoutMs`, a background timer automatically ends the session so a crashed agent cannot drain battery indefinitely.
- The user can end an active session at any time via a 1-tap **End Session** button in `MainActivity`.

### 2. Persistent Session Overlay (`TYPE_ACCESSIBILITY_OVERLAY`)
- The visual overlay is tied directly to the active session lifecycle (replacing the standalone always-on switch).
- While a session is active, `AccessibilityOverlayController` keeps a non-touchable, non-focusable `TYPE_ACCESSIBILITY_OVERLAY` window attached, showing a subtle screen border and top status pill (`android-use · Session` or `android-use · <label>`).
- During actions (`tap`, `long-press`, `swipe`, `type`, `key`, `launch`), action-specific markers and labels appear briefly before reverting to the persistent session banner.
- Before `takeScreenshot()`, the view temporarily suppresses drawing (`suppressDrawing = true`, leaving the window attached and transparent for one compositor frame) so screenshots delivered to AI agents contain zero overlay artifacts.
- When the session ends or times out, the overlay window is detached immediately.

### 3. Sleep Prevention & Wake-from-Sleep Boundaries
- **Sleep prevention (`keepAwake = true`)**: The persistent session overlay sets `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON`. This requires zero dangerous permissions, stays active continuously during screenshots (because the window remains visible while transparent), and is automatically released by Android the instant the session ends and the window detaches.
- **Wake from sleep**: `AndroidDevicePowerController.wakeScreen()` uses `PowerManager.ACQUIRE_CAUSES_WAKEUP` (normal `WAKE_LOCK` permission, bounded 3-second timeout) to turn the display on.
- **Lockscreen transparency**: `GET /v1/device/info`, `GET /v1/session`, and `POST /v1/device/wake` report `screenOn` (`PowerManager.isInteractive`) and `deviceLocked` (`KeyguardManager.isKeyguardLocked`). On devices with PIN/biometric locks, non-root apps cannot dismiss the keyguard; agents use `deviceLocked: true` to prompt the user to unlock.

## Consequences

### Positive
- Strong human-in-the-loop safety: the user always sees when an AI session is active and can terminate it in one tap.
- Reliable multi-minute agent runs without accidental display sleep, paired with automatic idle timeout protection.
- Clean screenshots with zero overlay bleed-through while keeping `FLAG_KEEP_SCREEN_ON` active.

### Negative
- Existing ad-hoc scripts must call `POST /v1/session/start` before taking screenshots or sending actions (and `POST /v1/session/end` afterward).
- On locked devices with PIN/biometrics, `wakeScreen` turns on the display but still requires user unlock before target apps are accessible.
