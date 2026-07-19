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

The examples are not universal camera limits. Production code must derive support, methods and ranges from the connected camera's advertised endpoint catalog and GET responses.

## Vendor-neutral API foundation

`common-api` now separates:

- `NativeFocusBracketingCapabilities`: advertised base support, required integer ranges and optional features;
- `NativeFocusBracketingRequest`: one fixed native sequence request;
- `NativeFocusBracketingProvider`: optional camera-backend extension;
- `NativeFocusBracketingSession`: an `AutoCloseable` prepared-setting scope;
- `IntegerSettingRange`: inclusive, minimum-relative stepped ranges.

A capability can be advertised but incomplete. Execution requires both the number-of-shots and focus-increment ranges.

## Transaction contract

`FocusBracketingSettingsTransaction` establishes the setting lifecycle that the Canon HTTP adapter will use:

1. validate the request against advertised capabilities;
2. read the complete original setting snapshot before any write;
3. disable an already active native sequence;
4. apply subordinate settings in deterministic order;
5. enable native bracketing last;
6. after capture, disable first and restore subordinate settings in reverse order;
7. restore the original enabled state last;
8. attempt the complete rollback after either an apply or restore failure, preserving suppressed failures.

Preparation does not trigger the shutter. This keeps camera-setting rollback independent from later capture, cancellation and media-ingest behavior.

## Remaining implementation work

The next increment under issue #144 must:

- map the Canon endpoint catalog and GET response bodies to the vendor-neutral capabilities;
- implement the transaction access adapter through the CCAPI HTTP client;
- trigger and observe the native sequence without assuming one response per frame;
- ingest every resulting file and record partial sequences;
- select native versus manual strategy explicitly in job/session metadata;
- retain the adaptive manual loop as fallback;
- validate endpoint/range evidence on the available PowerShot and EOS hardware without persisting changed camera settings.
