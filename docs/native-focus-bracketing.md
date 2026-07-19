# Native focus bracketing

Canon CCAPI can expose a camera-owned fixed focus-bracketing sequence in addition to the application's existing adaptive manual `drivefocus` loop.

## Why this is a separate strategy

The current manual stack loop can inspect every captured frame and adapt the next focus step. Native bracketing cannot preserve that behavior: the camera receives fixed settings and owns the complete multi-frame sequence after one shutter action.

Native bracketing is therefore an optional execution strategy, not a faster implementation of `setFocus(...)`.

## Canon CCAPI setting family

CCAPI Reference 1.4.0 defines the following independently advertised GET/PUT resources:

| Purpose | Stable path suffix | Documented value shape |
| --- | --- | --- |
| Enable sequence | `/shooting/settings/focusbracketing` | `enable` / `disable` |
| Number of shots | `/shooting/settings/focusbracketing/numberofshots` | integer range; example 2-999, step 1 |
| Focus increment | `/shooting/settings/focusbracketing/focusincrement` | integer range; example 1-10, step 1 |
| Exposure smoothing | `/shooting/settings/focusbracketing/exposuresmoothing` | `enable` / `disable` |
| Depth composition | `/shooting/settings/focusbracketing/depthcomposite` | `enable` / `disable` |
| Crop depth composition | `/shooting/settings/focusbracketing/cropdepthcomp` | `enable` / `disable` |

The examples are not universal camera limits. Production code derives support, methods and ranges from the connected camera's advertised endpoint catalog and GET responses.

## Vendor-neutral API

`common-api` separates:

- `NativeFocusBracketingCapabilities`: advertised base support, required integer ranges and optional features;
- `NativeFocusBracketingRequest`: one fixed native sequence request;
- `NativeFocusBracketingProvider`: optional camera-backend extension;
- `NativeFocusBracketingSession`: an `AutoCloseable` prepared-setting scope;
- `IntegerSettingRange`: inclusive, minimum-relative stepped ranges.

A capability can be advertised but incomplete. Execution requires both the number-of-shots and focus-increment ranges.

## Canon HTTP adapter

`CanonCcapiNativeFocusBracketingBackend` is the production `canon-ccapi` backend created by `CameraBackendFactory`. It extends the existing status/capture backend and implements `NativeFocusBracketingProvider` without changing the normal manual focus API.

The adapter applies the following safety rules:

1. one exact operation path must advertise both `GET` and `PUT`; methodless legacy lists never authorize writes;
2. when several API versions are present, the highest endpoint satisfying both methods is selected;
3. boolean support requires both `enable` and `disable` ability values;
4. integer limits come from the camera's `min`, `max` and `step` response, not from reference examples;
5. capability inspection performs GET requests only;
6. all original values are read again before the first write;
7. setting PUT requests use the Canon-compatible raw JSON body contract;
8. idempotent setting PUTs retry only Canon's `503 Device busy` response with bounded backoff;
9. `During shooting or recording` and `Mode not supported` are surfaced as distinct camera failures rather than being treated as unsupported hardware.

An inconsistent advertised response, such as an incomplete integer range or crop-depth support without depth-composition support, is rejected instead of silently inventing a capability.

## Transaction contract

`FocusBracketingSettingsTransaction` establishes the setting lifecycle used by the Canon adapter:

1. validate the request against advertised capabilities;
2. read the complete original setting snapshot before any write;
3. disable an already active native sequence;
4. apply subordinate settings in deterministic order;
5. enable native bracketing last;
6. after capture, disable first and restore subordinate settings in reverse order;
7. restore the original enabled state last;
8. attempt the complete rollback after either an apply or restore failure, preserving suppressed failures;
9. keep cleanup retryable after a transient restore failure until the full snapshot is restored.

Preparation does not trigger the shutter. Callers must close the returned session after capture, cancellation or failure.

## Remaining implementation work

Issue #144 still requires:

- trigger and observe the native sequence without assuming one HTTP response or media event per frame;
- ingest every resulting file and record partial sequences;
- select native versus manual strategy explicitly in job/session metadata;
- retain and test the adaptive manual loop as fallback;
- validate endpoint/range evidence on the available PowerShot and EOS hardware without persisting changed camera settings.

Until those items are complete, the adapter is a safe settings-preparation capability rather than an end-to-end native stacking workflow.
