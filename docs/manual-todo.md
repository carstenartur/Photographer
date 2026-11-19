# Manual follow-up

Use this checklist for work that needs access to physical equipment or a release decision. The [software backlog](../TODO.md) tracks implementation and checkout-runnable tests.

## CCAPI hardware evidence

- Run the [read-only capability collector](ccapi-capabilities.md#collect-a-model-and-firmware-snapshot) on the available PowerShot G5 X Mark II, PowerShot G7 X Mark III and EOS bodies. Record the model, firmware, connection mode, shooting mode and source commit. Compare the redacted snapshots with the existing G5 X Mark II CCAPI and G5 X Mark II / EOS R USB capture evidence. Keep snapshots from different firmware versions.
- For advertised Flip and Scroll modes, retrieve a frame, stop the stream and verify that a subsequent capture can proceed. Record the API version, operation, methods, stop behavior and any mode restrictions.
- If the camera advertises Multipart or RTP, capture the exact operation and methods. For Multipart, keep a boundary-framed sample and a cancellation exchange. For RTP, keep a redacted session description and the observed start and stop behavior. Follow the [live-view guide](ccapi-live-view.md).
- Exercise affected operations on any older camera or shooting mode before narrowing the fixed CCAPI v1.0 compatibility policy. Use the current production profile path and record the result by model and firmware.

Apply the collector's redaction rules to these artifacts. Record the results of physical operations separately from the read-only snapshots. The collector and the former evidence checklist are documented in [#183](https://github.com/carstenartur/Photographer/issues/183).

## Local bird observer on hardware

- Run the [local bird walkthrough](web-interface.md#photograph-birds-at-a-fixed-location) with each intended body, firmware and transport. Record the fixed location, focal length, focus/exposure mode and application commit. Measure the time from the first usable bird frame through shutter request, saved original and fresh live view after capture.
- Test preview-only mode, explicit arming, one photo per visit, repeated captures and stop during an in-progress photo. Check original-file integrity and that another camera workflow can start after the observer releases its lease.
- Observe ordinary background movement and empty frames as well as actual birds. Record missed visits and false triggers, especially at the chosen region boundary or in poor light. Check a longer session for connection recovery, available storage, battery and temperature behavior.

## Native focus bracketing on hardware

- Use each redacted capability snapshot to check whether the camera advertises the native settings, shutter and media/event transport for a full sequence.
- On a body that advertises the complete contract, run one bounded sequence. Record the shutter count, produced media or in-camera composition, complete or partial outcome, file integrity and restoration of the original settings. Check restoration on success and cancellation and, where practical, after an induced failure.
- If the available bodies lack the full contract, record that result and confirm the `AUTO` manual fallback. Include the model, firmware, connection and shooting mode in the report.

The [native-focus guide](native-focus-bracketing.md) describes the workflow and its transport preflight.

## D14 capture series

- Use the [minimum first acquisition](cr3-patterns/README.md#minimum-first-acquisition) for one fixed camera and manual setup. Capture the 35 patterns twice in consecutive A/B order. Retain the 70 original CR3 exposures and their 70 companion images.
- Record the setup, camera settings, original filenames and hashes using the [series layout and preflight](cr3-patterns/analysis-input-layout.md). Supply the checked series for calibration, repeat-noise analysis and Gray-code correlation.

The pattern PDFs, manifest, intake checker and analysis primitives are available. The full experiment design remains in [#249](https://github.com/carstenartur/Photographer/issues/249).

## Fresnel hardware acceptance

- Once the [real-camera adapter](../TODO.md#capture-integrations) supports the body, record the camera model, firmware, transport, original settings and both application versions. Run a bounded session under one lease. Verify restoration and release on completion, cancel and connection loss.
- For BOS, capture a reference and a disturbed condition with the operator confirming each change. For display photometric stereo, present each planned pattern on the physical display, record its hash and confirm it before triggering. Compare each source's length and SHA-256 on both hosts and check the corresponding Fresnel observation.
- Interrupt a transfer and repeat the original trigger after reconnecting. Compare the camera's exposure count and saved originals to the planned steps. Record any uncertain exposure before the operator decides how to proceed.

## Public release decisions

- Confirm the redistribution terms of the bundled pretrained weights before publishing a release that includes them. The [model notice](../ai-vision/src/main/resources/com/photographer/focusstack/ai/local/MODEL-NOTICE.md) records the specific upstream clarification request. This is separate from MIT/Apache source-license compatibility.
- Decide which first-party originals and embedded metadata may appear in the published history and downloads. Record the decision in the [publication audit](public-release.md#visibility-decision).
- Review redistribution rights and the applicable patent families for the intended distribution markets. Record the decisions in the [CR3 publication checklist](cleanroom/publication-checklist.md) and its linked review record.
- Decide whether to publish the reviewed repository history or the reviewed clean snapshot. Confirm the chosen visibility and exposed assets.

## Product decisions

Additional camera brands, multi-user/RBAC support and a dedicated hardware CI runner need a decision on intended users, hardware, compatibility, security and maintenance cost.
