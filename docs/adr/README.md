# Architecture Decision Records (ADR)

This directory documents the core architectural decisions and the incremental, phase-by-phase implementation plan for **android-use** (an Android Computer Use engine providing screen capture, UI inspection, tap/gesture injection, and text input for AI agents).

## Index

| ADR | Title | Status |
| --- | --- | --- |
| [0001](0001-layered-architecture-and-transport-abstraction.md) | Layered Architecture and Transport Abstraction | Accepted |
| [0002](0002-android-platform-and-accessibility-foundation.md) | Android Platform Target (`minSdk 33`) and Accessibility Foundation | Accepted |
| [0003](0003-screen-capture-strategy.md) | Phased Screen Capture Strategy (`AccessibilityService` First, Optional `MediaProjection` Later) | Accepted |
| [0004](0004-local-http-json-api-and-future-mcp-agent-modes.md) | Local HTTP/JSON API v1 with Extension Path to MCP and In-App Agent | Accepted |
| [0005](0005-phased-implementation-roadmap.md) | Incremental Phase-by-Phase Implementation Roadmap | Accepted |

## High-Level Architecture

```text
+-------------------------------------------------------------------+
|                        Transport / Clients                        |
|  [Phase 1-6] Local HTTP/JSON   [Phase 7] MCP    [Phase 8] In-App  |
|   (Ktor CIO, 127.0.0.1:8765)   (/mcp adapter)   Autonomous Agent  |
+-------------------------------+-----------------------------------+
                                |
                                v
+-------------------------------------------------------------------+
|                   Core Engine (Transport-Agnostic)                |
|  - DeviceController (serialized command queue + state queries)    |
|  - ScreenCapturer (interface: Accessibility -> MediaProjection)   |
|  - UiTreeInspector (AccessibilityNodeInfo -> compact JSON tree)   |
|  - InputDispatcher (tap, long-press, swipe, global actions)       |
|  - TextInputController (ACTION_SET_TEXT, clipboard/IME fallback)  |
|  - AppController (launch, list apps, wait helpers)                |
+-------------------------------+-----------------------------------+
                                |
                                v
+-------------------------------------------------------------------+
|                    Android Platform Layer (API 33+)               |
|  AccessibilityService | PackageManager | Clipboard | (MediaProj.) |
+-------------------------------------------------------------------+
```

## Phase Summary

| Phase | Focus | Milestone Outcome |
| --- | --- | --- |
| **Phase 0** | Project scaffold & test harness | Compilable Gradle project (`:protocol`, `:app`), empty `AccessibilityService`, status UI, green unit/Robolectric tests |
| **Phase 1** | Local HTTP server & device info | Embedded Ktor server on `127.0.0.1:8765`, `/v1/health` and `/v1/device/info` callable via `adb forward` |
| **Phase 2** | Screen capture | `ScreenCapturer` + `AccessibilityService.takeScreenshot()`, downscaling, JPEG/PNG, `/v1/screen/screenshot` |
| **Phase 3** | UI tree observation | Compact AI-friendly `AccessibilityNodeInfo` tree, `/v1/screen/tree` and combined `/v1/screen/state` |
| **Phase 4** | Pointer & navigation actions | Tap, long-press, swipe (`dispatchGesture`), global keys (`BACK`/`HOME`/`RECENTS`), coordinate mapping |
| **Phase 5** | Text input & IME | `ACTION_SET_TEXT`, clear/append, clipboard paste fallback, IME submit/enter |
| **Phase 6** | App control & reliability | Launch/list apps, wait helpers, serialized action queue, debug overlay, optional LAN auth |
| **Phase 7** | MCP server adapter | Streamable HTTP `/mcp` adapter over the same Core Engine |
| **Phase 8** | Optional `MediaProjection` & in-app agent | High-FPS `MediaProjectionScreenCapturer` + in-process autonomous agent loop |
