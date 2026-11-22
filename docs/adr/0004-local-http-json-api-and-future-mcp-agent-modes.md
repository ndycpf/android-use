# ADR-0004: Local HTTP/JSON API v1 with Extension Path to MCP and In-App Agent

- **Status**: Accepted
- **Date**: 2026-11-22

## Context

The first version of `android-use` needs a simple, language-agnostic, debuggable interface so any AI agent (Python, TypeScript, curl, Claude/Gemini/OpenAI tool wrappers) can observe and control an Android device. At the same time, the transport design must not box us out of:
- Exposing an **MCP (Model Context Protocol)** server directly from the device in a later phase.
- Running an **in-app autonomous agent** directly against the Core Engine.

Because the server controls the entire device UI, security defaults are critical—binding an unauthenticated control server to `0.0.0.0` on public Wi-Fi would be dangerous.

## Decision

### 1. Embedded HTTP Server: Ktor CIO
Use **Ktor Server (`ktor-server-cio`)** with `ktor-server-content-negotiation` and `kotlinx.serialization`:
- Pure Kotlin coroutine-native server with small footprint and clean Android compatibility.
- Built-in `testApplication` harness allows full HTTP route, serialization, and error-handling tests on the JVM without sockets or emulators.
- Easily hosts both REST endpoints (`/v1/...`) and future MCP Streamable HTTP endpoints (`/mcp`) inside the same server process.

### 2. Network Binding & Security Defaults
- **Default bind address**: `127.0.0.1:8765` (loopback only).
- **Recommended host connection**: `adb forward tcp:8765 tcp:8765`, then send requests to `http://127.0.0.1:8765/v1/...`.
- **Optional LAN mode (Phase 6)**: Explicit opt-in toggle in UI; when bound to a non-loopback address, **Bearer token authentication is mandatory** (auto-generated random token displayed in the app UI).

### 3. Versioned JSON API Contract (`/v1/...`)
All endpoints live under `/v1/` and return consistent JSON envelopes (except optional binary image mode on `/v1/screen/screenshot`):

- **Health & Device**
  - `GET /v1/health` — liveness + Accessibility Service connection state.
  - `GET /v1/device/info` — display dimensions, density, rotation, Android API level, server version.
- **Observation**
  - `GET /v1/screen/screenshot` — query params: `format=jpeg|png`, `quality=80`, `maxDimension=1280`, `encoding=base64|binary`.
  - `GET /v1/screen/tree` — normalized UI hierarchy tree from `AccessibilityService`.
  - `GET /v1/screen/state` — atomic/combined observation (foreground package, device info, screenshot, UI tree) to minimize round-trips.
- **Actions**
  - `POST /v1/actions/tap` — tap at coordinates (pixel or normalized `0.0..1.0` / screenshot-space).
  - `POST /v1/actions/long-press` — press-and-hold with configurable duration.
  - `POST /v1/actions/swipe` — swipe from `(x1, y1)` to `(x2, y2)` over `durationMs`.
  - `POST /v1/actions/key` — global actions (`BACK`, `HOME`, `RECENTS`, `NOTIFICATIONS`, `QUICK_SETTINGS`).
  - `POST /v1/actions/type` — text input (`text`, optional `nodeId`, `clearFirst`, `submit`).
  - `POST /v1/actions/clear` — clear focused or target text field.
  - `POST /v1/actions/ime` — trigger IME submit/enter action.
- **App & Wait Helpers**
  - `GET /v1/apps` — list launchable installed apps (`packageName`, `label`).
  - `POST /v1/apps/launch` — launch app by `packageName` or open `uri`.
  - `POST /v1/wait` — wait for duration or until text/element condition is met (with timeout).

### 4. Coordinate System Consistency
Agents frequently pick coordinates directly from a downscaled screenshot (e.g., `1280px` tall) or normalized `0..1` coordinates rather than native display pixels (e.g., `2400px` tall). Every pointer action accepts an explicit coordinate space:
- `pixel` (default): raw display pixels in the current orientation.
- `normalized`: floating-point `[0.0, 1.0]` mapped against current `displayWidth` / `displayHeight`.
- `image`: coordinates in a specified `imageWidth` x `imageHeight` (e.g., the last screenshot dimensions), scaled back to display pixels by `core/`.

### 5. Structured Error Responses
All errors return HTTP `4xx`/`5xx` with a deterministic JSON payload:
```json
{
  "error": {
    "code": "ACCESSIBILITY_NOT_CONNECTED",
    "message": "AndroidUseAccessibilityService is not enabled or not connected.",
    "retryable": true
  }
}
```

### 6. Extension Path
- **Phase 7 (MCP)**: Mount `/mcp` on the same Ktor CIO instance implementing MCP Streamable HTTP JSON-RPC (`tools/list`, `tools/call`) delegating directly to `DeviceController`.
- **Phase 8 (In-App Agent)**: Invoke `DeviceController` directly in-process from an on-device agent coroutine loop without going over HTTP.

## Consequences

### Positive
- Immediately usable from any language, agent framework, or `curl` during early development.
- Secure by default via loopback-only binding (`127.0.0.1`).
- Explicit coordinate spaces eliminate the most common Computer Use bug (screenshot-vs-display scaling mismatch).
- Smooth, non-breaking path to MCP and in-app agent execution.

### Negative
- HTTP/JSON adds minor serialization/base64 overhead compared to gRPC or binary sockets (negligible compared to LLM inference latency).
- Running a persistent HTTP server requires managing an Android ForegroundService (or keeping the app process alive) when operating across backgrounded apps.

## Alternatives Considered

- **MCP-first without REST**: Harder to debug quickly with `curl` and less universal for custom agent harnesses or simple scripts.
- **gRPC / Protobuf**: Faster binary framing, but heavier Android APK/tooling footprint and worse ad-hoc ergonomics.
- **NanoHTTPD**: Smaller dependency, but blocking thread-per-request model lacks coroutine integration and clean in-memory server testing compared to Ktor CIO.
