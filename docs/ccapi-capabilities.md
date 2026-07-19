# CCAPI capability status

The application exposes a redacted, camera-advertised capability snapshot at:

```text
GET /api/cameras/{profileId}/capabilities
```

This endpoint answers two different questions separately:

1. **What did the connected camera advertise?**
2. **What does this application implement for that advertised contract?**

A camera-advertised endpoint is not automatically an application feature, and an
application feature is not available on every camera. The response therefore keeps
`advertised` and `implemented` as independent fields.

## Availability values

| Value | Meaning |
| --- | --- |
| `AVAILABLE` | The camera advertised an exact endpoint with every required HTTP method, and the application implements the operation. |
| `UNSUPPORTED` | The exact endpoint is absent, or its method contract does not satisfy the operation. |
| `TEMPORARILY_UNAVAILABLE` | The operation is advertised and implemented, but the camera reported a documented temporary state. The stable `condition` contains only the typed reason code. |
| `ADVERTISED_NOT_IMPLEMENTED` | The camera advertises the operation, but the application intentionally does not implement it yet. |
| `METHOD_METADATA_UNKNOWN` | A legacy endpoint list advertises the path without method flags. The application does not claim full availability from the path alone. |

The implementation does not infer capabilities from camera model names or generic
substrings. It uses the normalized, all-version CCAPI endpoint catalog and exact
operation suffixes. When several versions advertise the same operation, the highest
matching version is selected.

## Redaction contract

The capability response may include:

- camera product name and firmware version returned by `deviceinformation`;
- relative CCAPI endpoint paths;
- API version groups;
- advertised HTTP methods;
- stable semantic capability identifiers and condition codes;
- lens mount state and lens name when the camera advertises a usable lens endpoint.

It does **not** include:

- camera host names, IP addresses, ports or absolute URLs;
- profile locations;
- serial numbers, GUIDs or MAC addresses;
- usernames, passwords or credential state;
- Digest realm, nonce, opaque or authorization values;
- raw downstream response bodies or headers.

The lens value is omitted unless the camera advertises the exact
`/devicestatus/lens` endpoint with usable method metadata. Fixed-lens PowerShot
cameras therefore do not receive an invented interchangeable-lens status.

## Current implemented capability groups

The snapshot currently reports application support for:

- device information;
- battery and storage status;
- advertised lens status;
- still capture;
- manual focus drive;
- live-view control and single-frame live view;
- media listing/download used by capture ingestion;
- event polling;
- camera-owned focus-bracketing settings when the production backend implements the native provider.

It also reports selected advertised gaps explicitly, including event monitoring,
live-view multipart streaming and RTP. These appear as
`ADVERTISED_NOT_IMPLEMENTED` rather than being silently presented as supported.

## Temporary states and public errors

The typed camera error taxonomy remains authoritative for operation failures. The
capability snapshot converts a documented temporary lens-status failure into
`TEMPORARILY_UNAVAILABLE`; authentication, transport and permanent contract errors
remain typed errors instead of being flattened into capability absence.

Only the documented temporary camera states marked retryable by the error taxonomy
should be retried automatically. A client must not derive retry behavior from HTTP
status alone.

## Non-CCAPI backends

Backends that do not implement `CameraCapabilityProvider` return a safe snapshot with
`catalogAvailable=false`, no endpoint list and no invented capability claims. Their
legacy host/path and backend-specific identifier are not copied into this diagnostic
response.
