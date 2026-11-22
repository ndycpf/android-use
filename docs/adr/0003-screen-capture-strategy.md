# ADR-0003: Phased Screen Capture Strategy (`AccessibilityService` First, Optional `MediaProjection` Later)

- **Status**: Accepted
- **Date**: 2026-11-22

## Context

AI agents using Computer Use rely on timely, accurately scaled screenshots of the Android screen. On Android 13+ (API 33+) without root access, there are two official screen capture mechanisms:

1. **`AccessibilityService.takeScreenshot()`**
   - Uses the already-granted Accessibility Service permission with no per-session consent dialog.
   - On-demand single-shot capture with low idle overhead.
   - Unaffected by Android 14/15 `MediaProjection` consent-token restrictions.
   - Subject to an OS-enforced minimum interval between consecutive captures (typically ~333ms–1000ms depending on OS/OEM; returns `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`).
   - Downscaling is performed after wrapping the returned `HardwareBuffer` into a `Bitmap`.

2. **`MediaProjection` (`VirtualDisplay` + `ImageReader`)**
   - No 1-second cooldown; supports high-frequency capture, animation-settle detection, and live streaming.
   - Supports GPU-side downscaling directly via `VirtualDisplay` dimensions.
   - Requires a `ForegroundService` (`foregroundServiceType="mediaProjection"`), persistent cast notification, and a mandatory system consent dialog on every session start.
   - On Android 14+, consent tokens cannot be reused across sessions and users can inadvertently select "Share a single app" instead of "Entire screen", breaking cross-app agent tasks.

Both approaches are blocked by `FLAG_SECURE` windows (such as banking/DRM screens) on non-rooted devices.

## Decision

We will adopt a **phased, interface-driven screen capture architecture**:

1. **Define a `ScreenCapturer` interface in `core/`**:
   ```kotlin
   interface ScreenCapturer {
       suspend fun capture(options: CaptureOptions): CaptureResult
   }
   ```
   `CaptureResult` always returns the encoded image bytes (`JPEG` or `PNG`), original display dimensions (`displayWidth`, `displayHeight`), captured/output image dimensions (`width`, `height`), scale factor, rotation, and capture timestamp so agents can map coordinates reliably regardless of backend.

2. **Phase 2 (Default Backend): `AccessibilityScreenCapturer`**
   - Wraps `AccessibilityService.takeScreenshot(Display.DEFAULT_DISPLAY, executor, callback)`.
   - Converts `HardwareBuffer` -> `Bitmap` (making sure to close the `HardwareBuffer` and recycle intermediate bitmaps immediately to prevent native memory leaks).
   - Supports configurable `maxDimension` downscaling (e.g., default `1280` px on the longest edge) and `format` (`jpeg` with configurable `quality`, or `png`).
   - Handles `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT` gracefully via configurable retry-after-backoff or returning a freshly cached frame within a short staleness window (e.g., <= 500ms) when requested.

3. **Phase 8 (Optional High-Frequency Backend): `MediaProjectionScreenCapturer`**
   - Implements the same `ScreenCapturer` interface as an opt-in capture mode when high-FPS polling, visual settle detection, or live preview streaming is needed.
   - Includes explicit UI consent handling, `MediaProjection.Callback.onStop()` cleanup, and orientation-change `VirtualDisplay` recreation.

## Consequences

### Positive
- Minimal permission friction in Phases 2–7: once Accessibility Service is enabled, agents can capture screens indefinitely without system consent popups.
- Lower battery/memory footprint during typical turn-based LLM loops (1–5 seconds per turn).
- Zero breaking API changes if/when `MediaProjectionScreenCapturer` is added later, because callers depend only on `ScreenCapturer` and `CaptureResult`.

### Negative
- Early phases (Phases 2–7) cannot perform rapid multi-FPS visual polling due to `takeScreenshot()` rate limiting.
- Software/CPU bitmap scaling adds minor latency per capture (typically tens of milliseconds on modern Android 13+ devices, acceptable for LLM turn times).

## Alternatives Considered

- **`AccessibilityService.takeScreenshot()` only (no abstraction)**: Saves minimal code now, but makes future high-FPS capture or streaming harder to integrate cleanly.
- **`MediaProjection` from Day 1**: Introduces significant Android 14/15 UX friction (re-prompting, single-app share pitfalls, foreground service management) before core Computer Use workflows are even validated.
