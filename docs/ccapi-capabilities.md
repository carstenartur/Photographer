# CCAPI capability status

The application exposes a redacted, camera-advertised capability snapshot at:

```text
GET /api/v1/cameras/{profileId}/capabilities
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

The normalized CCAPI catalog retains the advertised versions, operation paths
and HTTP methods for capability selection.

## Legacy endpoint compatibility

The production backend keeps all advertised CCAPI versions. Media listing, event observation and capability diagnostics match the whole operation below the version root. Each operation uses the highest compatible version, so an older readable `/contents` can serve media when a newer version omits GET. Polling continuation and monitoring require GET and DELETE on the same endpoint. Plain media listing and one-shot polling also accept exact legacy entries without method flags. The snapshot reports these entries as `METHOD_METADATA_UNKNOWN`. Native focus-bracketing settings require explicit GET and PUT.

A few inherited operations, such as basic device information and the regular shutter command, retain a fixed CCAPI v1.0 compatibility path when the selector finds no matching endpoint. A matching advertisement with known incompatible methods blocks that fallback.

Removing the fixed compatibility paths could regress control on older cameras or shooting modes with incomplete catalogs. Representative model, firmware and mode snapshots are tracked in the [manual checklist](manual-todo.md#ccapi-hardware-evidence). The snapshots and a physical check of affected operations are the basis for narrowing the compatibility policy.

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
- bounded media listing and range-safe media download;
- one-shot event polling;
- polling continuation when `/event/polling` advertises both GET and DELETE;
- event monitoring when `/event/monitoring` advertises both GET and DELETE;
- camera-owned focus-bracketing settings when the production backend implements the native provider.

Polling continuation and monitoring are cancellation-capable operations. They are
reported as `AVAILABLE` only when the same exact advertised endpoint provides both
GET and DELETE. A methodless legacy catalog therefore yields
`METHOD_METADATA_UNKNOWN`, not a blanket support claim.

Selected advertised gaps such as live-view multipart streaming and RTP remain
`ADVERTISED_NOT_IMPLEMENTED` rather than being silently presented as supported.

## Temporary states and public errors

The typed camera error taxonomy remains authoritative for operation failures. The
capability snapshot converts a documented temporary lens-status failure into
`TEMPORARILY_UNAVAILABLE`; authentication, transport and permanent contract errors
remain typed errors instead of being flattened into capability absence.

Only the documented temporary camera states marked retryable by the error taxonomy
should be retried automatically. A client must not derive retry behavior from HTTP
status alone.

Media and event cancellation is represented by the caller thread's interrupt state
and is reported as `CANCELLED`. It is not translated to camera disconnection or a
retryable transport failure.

## Non-CCAPI backends

Backends that do not implement `CameraCapabilityProvider` return a safe snapshot with
`catalogAvailable=false`, no endpoint list and no invented capability claims. Their
legacy host/path and backend-specific identifier are not copied into this diagnostic
response.

## Collect a model and firmware snapshot

Run the opt-in diagnostic on the computer that can reach the camera, with the camera in the mode you want to document. Use JDK 25 and Maven 3.9+. From the repository root, set the environment variables in the shell where Maven will run. In Bash:

```bash
read -r -p 'Camera base URL (scheme, host and port): ' CCAPI_EVIDENCE_URL
export CCAPI_EVIDENCE_URL
export CCAPI_EVIDENCE_MODE=WIFI_INFRASTRUCTURE
mvn -B -pl app -am verify -Dgroups=real-camera -DexcludedGroups=docker \
  -Dit.test=CcapiEndpointDiagnosticsIT -Dfailsafe.failIfNoSpecifiedTests=false
```

Use `WIFI_INFRASTRUCTURE`, `WIFI_CAMERA_ACCESS_POINT` or `ETHERNET` for the connection mode. If the camera requires Digest authentication, supply `CCAPI_EVIDENCE_USERNAME` and `CCAPI_EVIDENCE_PASSWORD` in the process environment. Run this exact test on its own. The broader hardware suite includes tests that actuate the camera.

The test creates `app/target/ccapi-capabilities.json`. Set `CCAPI_EVIDENCE_OUTPUT` to an absolute path for a different output. Each run requires a fresh filename. The diagnostic reads the same catalog, semantic capability snapshot and native focus-bracketing ranges as a normal camera profile. It can request the API roots, device information, advertised lens status and bracketing settings. It never starts live view, polls media, moves focus or triggers the shutter.

The JSON records the advertised product model and firmware, connection mode, exact recognized versioned operation paths, advertised/required methods, capability classifications and available native bracketing ranges. Unknown or private paths contribute only to `omittedEndpoints`. The export skips lens names, media names and raw response bodies. Inspect the model and firmware before publishing and record the shooting mode and source commit alongside the file. An unknown firmware value cannot establish firmware-specific support. Keep successive files so a later camera mode or firmware can be compared against the original.

The G5 X Mark II CCAPI capture, focus and manual-stacking tests and the G5 X Mark II / EOS R USB captures are the historical baseline. They establish different facts from a fresh capability snapshot. See the [hardware tests](../app/src/test/java/com/photographer/focusstack/camera/integration/realcamera/) and [camera coverage](camera-setup.md). See the [manual checklist](manual-todo.md#ccapi-hardware-evidence) for physical snapshots and [native-sequence validation](manual-todo.md#native-focus-bracketing-on-hardware).
