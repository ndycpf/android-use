# ADR-0001: Layered Architecture and Transport Abstraction

- **Status**: Accepted
- **Date**: 2026-11-22

## Context

`android-use` provides Android Computer Use capabilities (screen capture, UI tree inspection, touch gestures, text input, and app navigation) for AI agents.

The initial integration will expose a **local HTTP + JSON API** so external AI agents can control a device over `adb forward` (or optionally LAN). However, future goals explicitly include:

1. Exposing an **MCP (Model Context Protocol)** server directly from the device.
2. Supporting a **standalone in-app autonomous agent** that executes an LLM control loop on-device without going through HTTP.

If Android platform calls (`AccessibilityService`, `Bitmap` encoding, `GestureDescription`) are tightly coupled to HTTP route handlers or MCP tool schemas, adding MCP or an in-app agent will require duplicating fragile Android automation logic.

## Decision

We will structure the codebase around a **transport-agnostic Core Engine** with strict separation of concerns and a minimal two-module Gradle layout:

1. **`:protocol` (Pure Kotlin / JVM library module)**
   - Contains immutable request/response DTOs, coordinate types, normalized UI tree models, error codes, and `kotlinx.serialization` definitions.
   - Has **zero Android framework dependencies**, allowing fast JVM unit tests for schema compatibility and serialization.

2. **`:app` (Android application module)** organized into explicit internal layers:
   - `core/`: Transport-agnostic domain interfaces (`DeviceController`, `ScreenCapturer`, `UiTreeInspector`, `InputDispatcher`, `TextInputController`, `AppController`) and command serialization (mutex-backed execution queue).
   - `android/`: Concrete Android implementations backed by `AccessibilityService`, `PackageManager`, `ClipboardManager`, and (in a later phase) `MediaProjection`.
   - `server/`: Thin transport adapters that translate external requests into `DeviceController` calls:
     - `http/` (Phase 1+): Local HTTP + JSON routes (`/v1/...`).
     - `mcp/` (Phase 7): MCP Streamable HTTP adapter (`/mcp`).
   - `agent/` (Phase 8): In-process autonomous agent loop calling `DeviceController` directly.
   - `ui/`: Minimal Android UI for service status, Accessibility setup shortcut, server controls, and safety kill-switch.

### Execution Serialization

All mutating device actions (tap, swipe, text input, key press, app launch) are executed through a single serialized coroutine queue (`Mutex` or single-consumer actor) inside `DeviceController` so overlapping agent calls cannot interleave gestures or corrupt UI state.

## Consequences

### Positive
- HTTP v1, MCP, and in-app agent modes share **one** battle-tested Android automation implementation.
- `:protocol` and `core/` logic can be unit-tested on the JVM without an emulator.
- Transport layers stay thin (request validation, auth, serialization, HTTP status mapping only).

### Negative
- Slightly more upfront boilerplate (interfaces + DTO mapping) compared to calling `AccessibilityService` directly from HTTP handlers.
- Two Gradle modules (`:protocol`, `:app`) add minor Gradle configuration overhead (kept minimal by avoiding further module fragmentation until actually needed).

## Alternatives Considered

- **Single monolithic `:app` module with direct HTTP-to-Accessibility calls**: Faster in Phase 1, but causes high rework when introducing MCP and in-app agent modes and slows down schema/protocol testing.
- **Many fine-grained Gradle modules upfront (`:core`, `:capture`, `:input`, `:http`, `:mcp`, `:ui`)**: Premature complexity before the interfaces stabilize; we can extract additional modules later if build times or reuse justify it.
