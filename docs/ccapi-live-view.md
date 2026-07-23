# Canon CCAPI live-view transport

## Scope

The production Canon backend treats live view as a capability-selected transport, not as a fixed URL. Every decision is based on the exact endpoint paths and HTTP methods advertised by the connected camera.

This document describes application support. It does not claim that every Canon model or firmware advertises every mode.

## Vendor-neutral mode mapping

| Application mode | Canon endpoint | Required advertised methods | Application support |
|---|---|---:|---|
| `single-frame` | `/shooting/liveview/flip` | `GET` | Implemented and preferred |
| `cursor-stream` | `/shooting/liveview/scroll` | `GET`, `DELETE` | Implemented as bounded fallback |
| `multipart-stream` | `/shooting/liveview/multipart` | `GET`, `DELETE` | Advertised and diagnosed; consumer not implemented |
| `rtp-stream` | `/shooting/liveview/rtp` | `POST` | Advertised and diagnosed; SDP/RTP consumer not implemented |

`/shooting/liveview` is the separate control endpoint and must support `POST` when method metadata is available.

The production composition chooses the least complex compatible implementation:

1. the highest-version exact `single-frame` endpoint that advertises `GET`, or a legacy exact flip endpoint whose method metadata is absent;
2. otherwise the highest-version exact `cursor-stream` endpoint that explicitly advertises both `GET` and `DELETE`.

Scroll does not accept methodless catalogs because bounded ownership depends on a camera-advertised cancellation method. The backend never constructs a frame URL and never invokes an endpoint that explicitly advertises incompatible methods. A newer incompatible endpoint does not mask a compatible older version.

## Control lifecycle

The control client owns a small lifecycle:

- `STOPPED`
- `STARTING`
- `STARTED`
- `STOPPING`

Repeated frame requests send one control start request. Repeated stop requests send one control stop request. Start and stop bodies are stable raw UTF-8 JSON bytes:

```json
{"liveviewsize":"medium","cameradisplay":"on"}
```

```json
{"liveviewsize":"off","cameradisplay":"on"}
```

`Already started` is accepted as an idempotent control-start result. `Not started` is accepted as an idempotent control-stop result.

If frame retrieval returns the typed Canon condition `Live view not started`, the production backend performs exactly one corrective control start and retries the selected frame transport once. Other 503 conditions, including `Device busy`, `During shooting or recording`, `Taken in preparation` and `Mode not supported`, retain their typed sanitized failure and do not trigger a false live-view restart.

## Scroll ownership and framing

One scroll session owns:

- one long-running `GET /shooting/liveview/scroll`;
- one virtual reader thread;
- one latest-frame queue with capacity one;
- at most one matching `DELETE /shooting/liveview/scroll`;
- one bounded local close/wait transition.

HTTP clients remove transfer-encoding chunk boundaries before exposing the response body. The reader therefore does not assume that one `InputStream.read` call equals one Canon chunk. It extracts complete JPEGs from SOI/EOI markers across arbitrary read boundaries.

Memory is bounded:

- only the newest complete frame is retained when consumers are slower than the camera;
- a single JPEG may not exceed 32 MiB;
- incomplete or malformed end-of-stream data is rejected and never returned as a partial frame.

The default first/next-frame wait is ten seconds. Timeout or caller interruption sends the matching DELETE when the stream may still be active, closes the local response stream, interrupts the reader and waits up to three seconds for termination. Repeated application stop calls do not send duplicate DELETE operations.

Before capture, an active scroll GET is cancelled before the inherited shutter path stops the live-view control endpoint. This prevents a long-running frame operation from surviving into capture.

## Remaining stream modes

Multipart remains unimplemented until its boundary parser, bounded latest-frame queue and explicit DELETE cancellation have the same deterministic ownership guarantees as Scroll.

RTP remains unimplemented until SDP acquisition, target-address selection, receiver shutdown and bounded socket cancellation are owned by one transport component.

Advertised-but-unimplemented modes remain visible in capability diagnostics and are never silently treated as usable frame sources.

## Evidence boundary

Synthetic catalogs and transport tests cover exact method selection, version preference, flip priority, scroll fallback, arbitrary read fragmentation, bounded frame size, timeout, one matching DELETE, lifecycle idempotence, stable bodies and typed 503 behavior.

Model/firmware support claims remain evidence work under the CCAPI capability-snapshot issue and must be backed by redacted real-camera snapshots. Canon's specification defines the protocol contract, while actual model/firmware snapshots determine whether a camera advertises the required endpoints and methods.
