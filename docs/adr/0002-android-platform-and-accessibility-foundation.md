# ADR-0002: Android Platform Target (`minSdk 33`) and Accessibility Foundation

- **Status**: Accepted
- **Date**: 2026-11-22

## Context

Android's automation and observation APIs have evolved significantly across OS versions. Supporting legacy Android releases (API 26–29) requires complex fallbacks (`MediaProjection` as the only screenshot API, missing `ACTION_IME_ENTER`, older multi-window Accessibility behaviors, and extra legacy permission handling).

Additionally, `android-use` is developed in Kotlin with cloud-based CI/dev environments where hardware-accelerated Android Emulators (`/dev/kvm`) are not available, so testability on the JVM is critical.

## Decision

### 1. SDK and Toolchain Baseline
- **`minSdk = 33` (Android 13)** — targets modern devices and keeps the implementation simple by guaranteeing modern `AccessibilityService` APIs out of the box.
- **`targetSdk = 35`**, **`compileSdk = 36`** — aligned with the Android SDK platforms preinstalled in the cloud environment.
- **Language & Core Libraries**:
  - Kotlin + `kotlinx.coroutines` for asynchronous operations and callback-to-suspend bridging.
  - `kotlinx.serialization` (JSON) for strict, reflection-free DTO serialization.
  - Gradle Kotlin DSL (`build.gradle.kts`) with version catalog (`gradle/libs.versions.toml`).

### 2. `AccessibilityService` as the Automation Backbone
A dedicated `AndroidUseAccessibilityService` provides the primary non-root system capabilities:
- **UI hierarchy inspection**: `rootInActiveWindow` and `windows` (with `flagRetrieveInteractiveWindows`) to inspect visible elements, bounds, text, and interactive states.
- **Touch gesture injection**: `dispatchGesture(GestureDescription, ...)` for taps, long-presses, swipes, and drags.
- **Global navigation actions**: `performGlobalAction(...)` for `BACK`, `HOME`, `RECENTS`, `NOTIFICATIONS`, `QUICK_SETTINGS`, and `DISMISS_NOTIFICATION_SHADE`.
- **Text input**: `AccessibilityNodeInfo.ACTION_SET_TEXT`, `ACTION_PASTE`, and `AccessibilityAction.ACTION_IME_ENTER`.
- **Screen capture**: `takeScreenshot(Display.DEFAULT_DISPLAY, ...)` (see [ADR-0003](0003-screen-capture-strategy.md)).

No root access, `adb shell input`, or `uiautomator` host binary is required at runtime once Accessibility permission is granted by the user.

### 3. Verification Strategy Without `/dev/kvm`
Because the cloud development environment does not provide `/dev/kvm`, every phase must remain verifiable without booting an emulator:
- Pure JVM unit tests (`:protocol` and `core/` logic: coordinate transforms, UI tree pruning, action queue serialization, error mapping).
- Ktor server tests (`testApplication`) to verify HTTP/JSON contracts and status codes.
- Robolectric tests for Android components (`Service`, `Activity`, `Intent`, `AccessibilityNodeInfo` wrappers).
- Static checks: `./gradlew assembleDebug testDebugUnitTest lint`.
- Real-device manual smoke tests via `adb install` + `adb forward` + `curl` when validating end-to-end device behavior.

## Consequences

### Positive
- Clean, modern Android API surface without legacy API < 30 compatibility branches.
- Non-root operation on stock consumer Android 13+ devices.
- Fast, reliable local/cloud feedback loops via JVM + Robolectric tests.

### Negative
- Devices running Android 12 (API 32) or older are unsupported.
- Android's Restricted Settings policy (Android 13+) requires sideloaded APKs to enable "Allow restricted settings" in App Info once before activating the Accessibility Service. This setup step must be documented clearly in the app UI and `README.md`.
- Some end-to-end behaviors (e.g., actual `HardwareBuffer` screenshot pixels, real app gesture reception) can only be fully verified on a physical device or KVM-enabled emulator.

## Alternatives Considered

- **`minSdk = 26` or `minSdk = 30`**: Expands device reach slightly, but increases testing matrix and complexity (`minSdk 26` cannot use `takeScreenshot()` at all).
- **ADB-only host automation (`adb shell input` / `screencap` / `uiautomator dump`)**: Cannot run standalone on-device (blocks the in-app agent goal), suffers from slow shell process spawning, and breaks when USB/wireless ADB disconnects.
