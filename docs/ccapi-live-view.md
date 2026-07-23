# Canon CCAPI live-view transport

## Scope

The production Canon backend treats live view as a capability-selected transport, not as a fixed URL. Every decision is based on the exact endpoint paths and HTTP methods advertised by the connected camera.

This document describes application support. It does not claim that every Canon model or firmware advertises every mode.

## Vendor-neutral mode mapping

| Application mode | Canon endpoint | Required advertised methods | Application support |
|---|---|---:|---|
| `single-frame` | `/shooting/liveview/flip` | `GET` | Implemented |
| `cursor-stream` | `/shooting/liveview/scroll` | `GET`, `DELETE` | Advertised and diagnosed; consumer not implemented |
| `multipart-stream` | `/shooting/liveview/multipart` | `GET`, `DELETE` | Advertised and diagnosed; consumer not implemented |
| `rtp-stream` | `/shooting/liveview/rtp` | `POST` | Advertised and diagnosed; SDP/RTP consumer not implemented |

`/shooting/liveview` is the separate control endpoint and must support `POST` when method metadata is available.

The client chooses the least complex implemented mode. It currently selects the highest-version exact `single-frame` endpoint that either advertises `GET` or comes from a legacy catalog without method metadata. It never constructs a fallback frame URL and never invokes an endpoint that explicitly advertises an incompatible method.

A newer incompatible endpoint does not mask a compatible endpoint from an older advertised API version.

## Lifecycle

The production client owns a small synchronous lifecycle:

- `STOPPED`
- `STARTING`
- `STARTED`
- `STOPPING`

Repeated frame requests send one start request. Repeated stop requests send one stop request. Start and stop bodies are serialized once as stable raw UTF-8 JSON bytes:

```json
{"liveviewsize":"medium","cameradisplay":"on"}
```

```json
{"liveviewsize":"off","cameradisplay":"on"}
```

`Already started` is accepted as an idempotent start result. `Not started` is accepted as an idempotent stop result.

If frame retrieval returns the typed Canon condition `Live view not started`, the client performs exactly one corrective start and retries the frame once. Other 503 conditions, including `Device busy`, `During shooting or recording`, `Taken in preparation` and `Mode not supported`, retain their typed sanitized failure and do not trigger a false live-view restart.

## Cancellation and bounds

The implemented single-frame path inherits the production CCAPI connection and response timeouts. Start and stop requests inherit the existing bounded `Device busy` retry policy.

Scroll and multipart readers are intentionally not implemented until they have:

- a bounded read loop;
- explicit `DELETE` cancellation;
- deterministic close behavior on interruption, timeout and application shutdown;
- tests proving no duplicate start/stop/delete requests.

RTP remains unimplemented until SDP acquisition, target-address selection, receiver shutdown and bounded socket cancellation are owned by one transport component.

An advertised-but-unimplemented stream is reported as such; it is never silently treated as a usable live-view frame source.

## Evidence boundary

Synthetic catalogs and transport tests cover exact method selection, legacy methodless catalogs, version preference, lifecycle idempotence, stable bodies and typed 503 behavior. Model/firmware support claims remain evidence work under the CCAPI capability-snapshot issue and must be backed by redacted real-camera snapshots.
