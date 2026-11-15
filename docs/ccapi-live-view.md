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

For example, `/ccapi/ver120/shooting/liveview/flip` identifies the version 120 flip operation. A path such as `/ccapi/ver120/vendor/shooting/liveview/flip` remains visible in the raw catalog but cannot enable the flip capability or receive a live-view request. The same comparison applies to control, scroll, multipart and RTP.

The production backend reports live-view support when it can both start live view and read frames. It chooses the least complex compatible implementation:

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

## Deferred stream modes

The production backend implements Flip and Scroll. Multipart and RTP remain deferred and appear as `ADVERTISED_NOT_IMPLEMENTED` when a camera advertises them. A camera that only advertises these modes has no usable live-view transport in Photographer.

Multipart needs a boundary-framed camera sample and an observed DELETE cancellation exchange. Its eventual consumer must bound frame size, keep only the latest frame and close the response before the camera lease can be released. RTP needs a redacted SDP exchange and observed start/stop behavior. Its eventual consumer must bound receiver startup and shutdown, restrict the negotiated destination and close its sockets before releasing the lease. Enabling either mode without this evidence could leave a stream active when the next capture starts.

## Evidence boundary

Synthetic catalogs and transport tests cover exact method selection, version preference, flip priority, scroll fallback, arbitrary read fragmentation, bounded frame size, timeout, one matching DELETE, lifecycle idempotence, stable bodies and typed 503 behavior.

[Issue #183](https://github.com/carstenartur/Photographer/issues/183) tracks redacted model/firmware catalogs and physical validation of the selected transport. Canon's specification defines the protocol contract, while actual model/firmware snapshots determine whether a camera advertises the required endpoints and methods.

For a new camera or firmware, record the model, firmware, API version group, advertised path and method flags from its redacted catalog. Confirm that a frame can be retrieved and that stopping the selected mode releases the camera before capture. Multipart requires a boundary-framed sample and a verified cancellation exchange. RTP requires a redacted session description and verified start and stop behavior. Until then, a catalog entry identifies an advertised transport; it supplies no evidence of a successful stream.
