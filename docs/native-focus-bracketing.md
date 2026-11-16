# Native focus bracketing

Canon CCAPI can expose a camera-owned fixed focus-bracketing sequence in addition to the application's adaptive manual `drivefocus` loop.

## Strategy selection

Every stacking request now selects one explicit `captureStrategy`:

| Value | Behaviour |
| --- | --- |
| `MANUAL` | Uses the existing application-controlled capture/analyse/focus loop. This remains the backward-compatible default. |
| `NATIVE` | Requests native execution and reports a terminal error when the settings or transport fail. |
| `AUTO` | Selects native execution when the backend exposes native execution and media support and validates the requested settings. It records a fallback reason when those checks select the manual loop. |

[Issue #144](https://github.com/carstenartur/Photographer/issues/144) tracks validating the advertised shutter, media and event operations before applying native camera settings.

Example request parameters:

```json
{
  "startPosition": 0,
  "stepSize": 2,
  "maxFrames": 20,
  "abortThreshold": 0.01,
  "motionDetectionEnabled": false,
  "qualityMode": "HIGH",
  "captureStrategy": "NATIVE",
  "nativeFocusBracketing": {
    "numberOfShots": 20,
    "focusIncrement": 2,
    "exposureSmoothing": true,
    "depthComposition": false,
    "cropDepthComposition": false
  }
}
```

When a non-manual strategy omits `nativeFocusBracketing`, the service derives the fixed request from `maxFrames` and `stepSize`, with optional features disabled.

## Why native execution is separate

The manual stack loop inspects every frame and can adapt the next focus step. Native bracketing cannot preserve that behaviour: the camera receives fixed settings and owns the complete multi-frame sequence after one shutter action.

Native bracketing is therefore a separate execution strategy, not a faster implementation of `setFocus(...)`. Manual adaptive stepping remains available and is the fallback selected by explicit `AUTO` only.

## Canon CCAPI setting family

CCAPI Reference 1.4.0 defines independently advertised GET/PUT resources:

| Purpose | Stable path suffix |
| --- | --- |
| Enable sequence | `/shooting/settings/focusbracketing` |
| Number of shots | `/shooting/settings/focusbracketing/numberofshots` |
| Focus increment | `/shooting/settings/focusbracketing/focusincrement` |
| Exposure smoothing | `/shooting/settings/focusbracketing/exposuresmoothing` |
| Depth composition | `/shooting/settings/focusbracketing/depthcomposite` |
| Crop depth composition | `/shooting/settings/focusbracketing/cropdepthcomp` |

Reference examples are not universal camera limits. Production code derives support, methods and ranges from the connected camera's exact advertised endpoint catalog and GET responses.

## Vendor-neutral contracts

`common-api` separates:

- `NativeFocusBracketingCapabilities`: advertised base support, required integer ranges and optional features;
- `NativeFocusBracketingRequest`: one fixed native sequence request;
- `NativeFocusBracketingProvider`: transactional settings preparation;
- `NativeFocusBracketingExecutionProvider`: exactly one trigger for a prepared session;
- `NativeFocusBracketingSession`: an `AutoCloseable` prepared-setting scope;
- `NativeFocusBracketingExecutionResult`: expected count, observed media, ingested frame ids, completion state, partial-failure reason and restoration state;
- `CameraMediaProvider`: bounded media snapshots, event observation and range-safe transfer.

No Canon-specific type enters `StackingJobService`.

## Settings transaction

`FocusBracketingSettingsTransaction` provides the setting lifecycle:

1. validate the request against advertised capabilities;
2. read the complete original snapshot before any write;
3. disable an already active sequence;
4. apply subordinate settings in deterministic order;
5. enable native bracketing last;
6. after execution, disable first and restore subordinate settings in reverse order;
7. restore the original enabled state last;
8. attempt the complete rollback after apply or restore failure and preserve suppressed failures;
9. keep cleanup retryable after a transient restore failure.

The production wrapper owns the prepared session. Foreign, closed and already-triggered sessions are rejected.

## Trigger contract

Preparation never triggers capture. After preparation and the pre-trigger media snapshot, the Canon adapter:

1. resolves the highest exact `/shooting/control/shutterbutton` endpoint that advertises `POST`;
2. stops live view only through the existing catalog-aware Canon path, based on established PowerShot evidence that active live view can block capture/media access;
3. sends exactly one normal shutter POST;
4. performs no capture-result polling or download in the trigger path.

The higher API version does not win unless it satisfies the required method contract.

## Observation and ingestion

The native executor snapshots known media before the shutter action. It then selects the best advertised observation mode:

1. binary monitoring when exact GET and DELETE are available;
2. polling continuation when exact GET and DELETE are available;
3. bounded one-shot polling otherwise.

An event is only a wake-up hint. The executor refreshes the bounded media snapshot after each event and computes the difference from the pre-trigger state. It therefore does not assume one event or one HTTP response per generated frame.

Each unique new media reference is ingested once. Transfers use bounded 16 MiB ranges and are assembled up to a 1 GiB per-frame safety limit. Successfully ingested files remain in `FrameDataStore` and in job frame metadata even if later files, timeout, cancellation or restoration fail.

## Terminal outcomes

`NativeFocusBracketingExecutionResult` reports:

- `COMPLETE`: every expected media file was observed and ingested and settings were restored;
- `PARTIAL`: some media was preserved but the sequence was incomplete;
- `TIMED_OUT`: no media arrived before the bounded deadline;
- `CANCELLED`: caller cancellation interrupted observation and restoration still ran;
- `FAILED`: trigger, transfer, capability or restoration failed.

Incomplete restoration always forces `FAILED`, even if all image files were already ingested. The failure reason and `restorationComplete=false` remain visible in persisted job execution metadata.

## Persisted job metadata

Jobs retain:

- requested and selected strategy;
- effective frame count and focus increment;
- effective exposure-smoothing/depth-composition options;
- native completion state;
- observed and ingested counts;
- restoration state;
- explicit fallback or partial-failure detail.

Existing clients that use the former six-field `StackingParameters` constructor or omit `captureStrategy` continue to receive the manual strategy.

## Validation boundary

Synthetic tests cover grouped media discovery, one trigger, partial timeout, cancellation before trigger, restoration failure, exact compatible shutter selection, range ingestion, strategy persistence and `AUTO` fallback. The [manual checklist](manual-todo.md#native-focus-bracketing-on-hardware) records the physical checks, settings restoration and redaction requirements.
