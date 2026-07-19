# CCAPI media and event contract

The production Canon CCAPI backend implements the optional `CameraMediaProvider`
contract for bounded media discovery, range-safe transfer and added-content
observation. The implementation follows the camera-advertised endpoint and method
catalog; it does not construct support claims from model names.

## Version-aware media hierarchy

Canon uses different response shapes across CCAPI generations:

- version 1.0 returns absolute `url` values;
- versions 1.1 through 1.4 return relative `path` values;
- version 1.4 adds a folder level such as `DCIM`, `XFVC` or `CRM` between storage and directory.

The adapter normalizes all three forms to a `CameraMediaReference` containing only:

- storage name;
- relative CCAPI resource path;
- file name and normalized format;
- advertised API version.

Absolute camera URLs are never returned through the reusable contract.

## Listing bounds

`MediaQuery` requires explicit bounds and enforces repository-wide maxima:

| Bound | Maximum |
| --- | ---: |
| Returned media items | 1,000 |
| Visited content pages | 100 |
| Directory/format count operations | 100 |

The adapter first obtains the camera-reported content count and page count, then
visits only the permitted number of list pages. `MediaPage.truncated` indicates that
a caller-selected or implementation safety limit prevented a complete result.

Format filters are converted to Canon query tokens (`JPG`/`JPEG` to `jpeg`,
`HEIF`/`HIF` to `hif`) and contain alphanumeric tokens only.

## Transfer contract

`downloadMedia(reference, offset, maxBytes)` always sends an HTTP byte range and
limits one returned chunk to 16 MiB.

The adapter validates:

- non-negative offsets and overflow-safe end offsets;
- same-origin relative media references;
- `206 Partial Content` and its `Content-Range` start offset;
- bounded response bodies;
- the reported total size when Canon supplies it.

A camera that ignores a non-zero range request is rejected as a protocol mismatch.
Callers can continue with the returned offset and data length until `complete=true`.

## Event modes

Three observation modes are exposed:

| Mode | Endpoint | Required advertised methods | Behaviour |
| --- | --- | --- | --- |
| `POLLING` | `/event/polling` | GET | Repeats bounded one-shot polling until media arrives or the deadline expires. |
| `POLLING_CONTINUATION` | `/event/polling?continue=on` | GET and DELETE | Uses Canon's continuing poll and cancels the operation with DELETE. |
| `MONITORING` | `/event/monitoring` | GET and DELETE | Waits for the monitoring response and cancels the operation with DELETE. |

Continuation and monitoring are rejected unless the exact selected endpoint
advertises DELETE. Methodless legacy catalogs are insufficient for starting an
operation that requires explicit cancellation.

Event payloads are searched only for same-origin media paths under `/contents/`.
The resulting `CameraMediaReference` contains no camera address or raw event payload.

## Cancellation and timeout

The observation timeout must be positive and no longer than five minutes. Waiting is
performed on a bounded task and the operation is cancelled when the deadline expires.

A caller cancels listing, transfer or observation by interrupting its thread. The
interrupt flag is preserved and the typed reason is `CANCELLED`; ordinary
cancellation is not reported as camera disconnection, timeout or retryable transport
failure.

DELETE is used only to stop a continuing polling or monitoring request. The adapter
has no media-delete, directory-delete or metadata-modification operation, so automated
capture and ingestion cannot erase or alter camera files through this contract.

## Network and diagnostic boundary

Every URL or path returned by the camera is resolved against the configured CCAPI
origin and must retain the same scheme, host and effective port. User information,
queries and fragments in camera-returned resource identifiers are rejected.

Public media references and capability diagnostics exclude:

- IP addresses, host names, ports and absolute URLs;
- usernames, passwords and Digest challenge material;
- serial numbers, GUIDs and MAC addresses;
- raw response bodies and headers.

The Canon Android sample remains behavioural evidence only. Its ICMP-based Wi-Fi
monitoring and logging practices are not part of the production session or media
contract.

## Current integration boundary

`CameraBackendFactory` creates `CanonCcapiProductionBackend`, which composes the
existing status/native-focus backend with the media client. Both use the same cached,
all-version endpoint catalog and the same profile-scoped authenticated transport.

The older capture implementation remains compatible while native sequence
orchestration can consume `CameraMediaProvider` directly. Migrating every legacy
capture path is intentionally separate from this transport contract.
