# Canon CCAPI live-view transport

## Scope

The production Canon backend treats live view as a capability-selected transport, not as a fixed URL. Every decision is based on the exact endpoint paths and HTTP methods advertised by the connected camera.

This document describes application support. It does not claim that every Canon model or firmware advertises every mode.

## Vendor-neutral mode mapping

| Application mode | Canon endpoint | Required advertised methods | Application support |
|---|---|---:|---|
| `single-frame` | `/shooting/liveview/flip` | `GET` | Implemented |
| `cursor-stream` | `/shooting/liveview/scroll` | `GET`, `DELETE` | Implemented as a bounded one-frame pull with mandatory DELETE cleanup |
| `multipart-stream` | `/shooting/liveview/multipart` | `GET`, `DELETE` | Advertised and diagnosed; consumer not implemented |
| `rtp-stream` | `/shooting/liveview/rtp` | `POST` | Advertised and diagnosed; SDP/RTP consumer not implemented |

`/shooting/liveview` is the separate control endpoint and must support `POST` when method metadata is available.

The client chooses the least complex implemented mode. It first selects the highest-version exact `single-frame` endpoint that either advertises `GET` or comes from a legacy catalog without method metadata. When no compatible single-frame endpoint exists, it selects the highest compatible `cursor-stream` endpoint. It never constructs a fallback frame URL and never invokes an endpoint that explicitly advertises an incompatible method.

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

The single-frame and cursor-stream paths inherit the production CCAPI connection and 30-second response timeout. Start and global stop requests inherit the existing bounded `Device busy` retry policy.

The cursor consumer:

- scans at most 64 KiB before JPEG SOI;
- retains at most one 4 MiB JPEG;
- has no producer queue or unbounded buffering;
- returns after the first JPEG EOI marker;
- sends `DELETE` after success, malformed data, interruption, timeout, or typed camera failure;
- accepts both Canon's documented `image/octet-stream` and firmware-compatible `application/octet-stream`;
- closes one stale cursor and retries once for typed `Already started`.

Multipart remains unimplemented until equivalent MIME-boundary, part-size, cancellation, and close guarantees are tested. RTP remains unimplemented until SDP acquisition, target-address selection, receiver shutdown, and bounded socket cancellation are owned by one transport component.

Advertised-but-unimplemented modes remain diagnostic-only and are never silently treated as usable frame sources.

## Evidence boundary

Synthetic catalogs and transport tests cover exact method selection, legacy methodless catalogs, version preference, lifecycle idempotence, stable bodies and typed 503 behavior. Model/firmware support claims remain evidence work under the CCAPI capability-snapshot issue and must be backed by redacted real-camera snapshots.
