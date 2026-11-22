# ADR-0005: Incremental Phase-by-Phase Implementation Roadmap

- **Status**: Accepted
- **Date**: 2026-11-22

## Context

Building an Android Computer Use platform touches multiple system subsystems (Accessibility Service, foreground service lifecycle, embedded HTTP server, screenshot capture, UI hierarchy serialization, gesture dispatch, text input, and eventually MCP and on-device agent orchestration).

Implementing everything in one large drop increases debugging risk—especially since hardware-accelerated emulators are unavailable in the cloud VM. We need a **small, incremental, phase-by-phase plan** where each phase adds one cohesive vertical slice, compiles cleanly, passes automated tests, and leaves `main` in a working state.

## Decision

Implementation will proceed sequentially through **9 small phases (Phase 0 through Phase 8)**. Each phase is committed directly to `main` (per repository workflow) only after its automated verification passes (`./gradlew assembleDebug testDebugUnitTest lint`).

---

### Phase 0: Project Scaffold & Build/Test Baseline
**Goal**: Establish a minimal, green Android + Kotlin Gradle project with the layered skeleton from [ADR-0001](0001-layered-architecture-and-transport-abstraction.md) and [ADR-0002](0002-android-platform-and-accessibility-foundation.md).

- **Deliverables**:
  - Gradle Kotlin DSL root (`settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, Gradle Wrapper `./gradlew`, `.gitignore`).
  - `:protocol` JVM module with base error/health DTOs and `kotlinx.serialization` setup.
  - `:app` Android module (`minSdk = 33`, `targetSdk = 35`, `compileSdk = 36`).
  - `AndroidUseAccessibilityService` skeleton + `res/xml/accessibility_service_config.xml` + `AndroidManifest.xml` declarations.
  - Minimal `MainActivity` displaying Accessibility Service connection status and a button to open Android Accessibility Settings.
  - Unit test + Robolectric harness.
- **Out of Scope**: HTTP server, screenshots, gestures, text input.
- **Verification**:
  - `./gradlew assembleDebug testDebugUnitTest lint` passes cleanly.
  - Unit tests verify `:protocol` JSON serialization and service status mapping.

---

### Phase 1: Local HTTP/JSON Server & Device Info
**Goal**: Stand up the embedded Ktor CIO server on `127.0.0.1:8765` and prove end-to-end HTTP request handling.

- **Deliverables**:
  - `DeviceController` core interface + `ServerForegroundService` (keeps embedded server alive while other apps are in the foreground).
  - Ktor CIO server (`127.0.0.1:8765`) with JSON content negotiation and structured error handling ([ADR-0004](0004-local-http-json-api-and-future-mcp-agent-modes.md)).
  - `GET /v1/health` (server status, accessibility connected boolean).
  - `GET /v1/device/info` (display width/height, densityDpi, rotation, SDK version, foreground package if available).
  - `MainActivity` controls to start/stop the server and display the `adb forward tcp:8765 tcp:8765` command.
- **Out of Scope**: Screen capture, UI tree, input actions.
- **Verification**:
  - Ktor `testApplication` tests for `/v1/health`, `/v1/device/info`, and error envelopes.
  - Manual device check: `adb forward tcp:8765 tcp:8765 && curl http://127.0.0.1:8765/v1/health`.

---

### Phase 2: Screen Capture (`AccessibilityService.takeScreenshot`)
**Goal**: Enable AI agents to capture the current Android screen reliably with downscaling and format control ([ADR-0003](0003-screen-capture-strategy.md)).

- **Deliverables**:
  - `ScreenCapturer` interface + `AccessibilityScreenCapturer` implementation.
  - `HardwareBuffer` -> `Bitmap` conversion with strict resource cleanup (`HardwareBuffer.close()`, `Bitmap.recycle()`).
  - Configurable `maxDimension` downscaling, `format` (`jpeg` / `png`), `quality`, and `encoding` (`base64` JSON vs raw `binary` image).
  - Rate-limit handling for `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT` (bounded retry or optional short-TTL cached frame).
  - `GET /v1/screen/screenshot`.
- **Out of Scope**: `MediaProjection`, video streaming.
- **Verification**:
  - Unit tests for dimension scaling math, rate-limit backoff/cache policy, and Ktor route query validation + binary/base64 responses.
  - Manual device check: `curl "http://127.0.0.1:8765/v1/screen/screenshot?encoding=binary&format=jpeg" -o screen.jpg`.

---

### Phase 3: UI Hierarchy Inspection & Combined State
**Goal**: Provide a compact, structured representation of on-screen UI elements so agents can ground actions by both vision and accessibility metadata.

- **Deliverables**:
  - `UiTreeInspector` traversing active window(s) via `AccessibilityService`.
  - Pruning/normalization pipeline: assigns deterministic ephemeral `nodeId`s, filters empty non-interactive layout wrappers, and exports `text`, `contentDescription`, `resourceId`, `className`, `bounds`, `center`, and flags (`clickable`, `editable`, `scrollable`, `checked`, `focused`, `enabled`).
  - Safeguards against huge/deep view hierarchies (max depth / max node cap).
  - `GET /v1/screen/tree` and `GET /v1/screen/state` (combined device info + foreground app + screenshot + UI tree).
- **Out of Scope**: Executing actions by node selector (added in Phases 4–5).
- **Verification**:
  - Unit tests for tree pruning, depth/node caps, bounds/center calculation, and deterministic node IDs.
  - Ktor route tests for `/v1/screen/tree` and `/v1/screen/state`.

---

### Phase 4: Pointer Gestures & Global Navigation Actions
**Goal**: Allow agents to interact with the screen via touch gestures and system navigation keys.

- **Deliverables**:
  - `CoordinateMapper` supporting `pixel`, `normalized` (`0.0..1.0`), and `image` coordinate spaces with bounds clamping/validation.
  - `InputDispatcher` using `AccessibilityService.dispatchGesture()` (`GestureDescription`) and `performGlobalAction()`.
  - Serialized action execution queue (`Mutex`) inside `DeviceController`.
  - Endpoints:
    - `POST /v1/actions/tap`
    - `POST /v1/actions/long-press`
    - `POST /v1/actions/swipe`
    - `POST /v1/actions/key` (`BACK`, `HOME`, `RECENTS`, `NOTIFICATIONS`, `QUICK_SETTINGS`)
- **Out of Scope**: Multi-touch pinch gestures (can be added incrementally if needed), text input.
- **Verification**:
  - Unit tests for `CoordinateMapper` (portrait/landscape/scaled image/normalized), gesture path generation, and serialized queue ordering.
  - Ktor route tests for payload validation and out-of-bounds error codes.

---

### Phase 5: Text Input & Focused Field Operations
**Goal**: Enable reliable text entry across native Android views, Compose inputs, and WebViews.

- **Deliverables**:
  - `TextInputController`:
    - Locate target node by optional `nodeId` or current `FOCUS_INPUT`.
    - Primary strategy: `ACTION_SET_TEXT` (supports `clearFirst` and append modes).
    - Fallback strategy: clipboard copy + `ACTION_PASTE` (with best-effort clipboard restore) when `ACTION_SET_TEXT` is unsupported or returns false (common in WebViews/custom editors).
    - Optional IME submission via `AccessibilityAction.ACTION_IME_ENTER`.
  - Endpoints:
    - `POST /v1/actions/type`
    - `POST /v1/actions/clear`
    - `POST /v1/actions/ime`
- **Out of Scope**: Virtual custom IME APK (only revisit if `ACTION_SET_TEXT` + `ACTION_PASTE` proves insufficient for target apps).
- **Verification**:
  - Unit tests for node targeting, clear/append text composition, fallback trigger logic, and route validation.

---

### Phase 6: App Control, Wait Helpers & Operational Hardening
**Goal**: Make multi-step agent sessions reliable, debuggable, and safe for real-world usage.

- **Deliverables**:
  - `AppController`: list launchable apps (`GET /v1/apps`), launch by `packageName` or open `uri` (`POST /v1/apps/launch`).
  - `POST /v1/wait`: sleep or poll UI tree until a target text/node appears or disappears (with timeout).
  - Optional LAN bind mode with mandatory Bearer token authentication (generated and viewable/rotatable in UI). *(Note: Tailscale/LAN bind with Bearer auth was delivered early after Phase 2 to enable cable-free device testing.)*
  - Optional visual debug overlay (briefly highlights tap coordinates and swipe paths on screen) + emergency stop notification action.
  - **UI tree payload optimization** (deferred from Phase 3 after real-device Gmail tree inspection): collapse `resourceId`-only non-interactive layout wrappers (`FrameLayout`/`LinearLayout`/`View` without text, description, or interactive flags) and omit redundant child `packageName` / default false flags to reduce LLM token overhead.
- **Out of Scope**: MCP protocol and LLM API client.
- **Verification**:
  - Unit tests for wait polling/timeouts, auth middleware (loopback vs LAN token enforcement), and app query filtering.

---

### Phase 7: MCP (Model Context Protocol) Server Adapter
**Goal**: Allow MCP-compatible AI agents to connect directly to the Android device without an external wrapper server.

- **Deliverables**:
  - MCP Streamable HTTP endpoint (`/mcp`) mounted on the existing Ktor CIO server.
  - Tool definitions mapped 1:1 to `DeviceController` (`screenshot`, `get_ui_tree`, `get_screen_state`, `tap`, `long_press`, `swipe`, `press_key`, `type_text`, `launch_app`, `list_apps`, `wait`).
  - Image content responses returning screenshot blocks natively per MCP tool result specification.
- **Out of Scope**: On-device LLM orchestration.
- **Verification**:
  - Ktor `testApplication` tests covering MCP JSON-RPC `initialize`, `tools/list`, `tools/call`, and structured tool error responses.

---

### Phase 8: Optional `MediaProjection` Capture & In-App Autonomous Agent
**Goal**: Support advanced capture workflows and standalone on-device autonomous execution.

- **Deliverables**:
  - **8a (`MediaProjectionScreenCapturer`)**: Opt-in high-frequency `ScreenCapturer` backend using `MediaProjection` + `VirtualDisplay` + `ImageReader`, with Android 14+ single-session consent flow and `onStop()` teardown.
  - **8b (Standalone In-App Agent)**: Minimal on-device `AgentLoop` calling `DeviceController` directly in-process, pluggable LLM HTTP client interface, task prompt UI, step log viewer, and floating emergency stop button.
- **Verification**:
  - Unit tests for `AgentLoop` state transitions, step budgets, cancellation/stop handling, and `ScreenCapturer` backend selection.

---

## Consequences

### Positive
- Every phase delivers an immediately testable, incremental capability on `main`.
- Early phases (0–5) establish a complete end-to-end Computer Use loop (observe -> tap/swipe -> type) with minimal complexity.
- Clean boundaries ensure Phases 7 (MCP) and 8 (`MediaProjection` + in-app agent) extend rather than rewrite earlier phases.

### Negative
- Some later conveniences (LAN auth, wait helpers, MCP) are deferred until the core capture/action loop is proven.
- Keeping every phase small requires discipline to defer nice-to-have features to their designated phase.
