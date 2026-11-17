# Manual follow-up

Use this checklist for work that needs access to physical equipment or a release decision. The [software backlog](../TODO.md) tracks implementation and checkout-runnable tests.

## CCAPI hardware evidence

- Run the [read-only capability collector](ccapi-capabilities.md#collect-a-model-and-firmware-snapshot) on the available PowerShot G5 X Mark II, PowerShot G7 X Mark III and EOS bodies. Record the model, firmware, connection mode, shooting mode and source commit. Compare the redacted snapshots with the existing G5 X Mark II CCAPI and G5 X Mark II / EOS R USB capture evidence. Keep snapshots from different firmware versions.
- For advertised Flip and Scroll modes, retrieve a frame, stop the stream and verify that a subsequent capture can proceed. Record the API version, operation, methods, stop behavior and any mode restrictions.
- If the camera advertises Multipart or RTP, capture the exact operation and methods. For Multipart, keep a boundary-framed sample and a cancellation exchange. For RTP, keep a redacted session description and the observed start and stop behavior. Follow the [live-view guide](ccapi-live-view.md).
- Exercise affected operations on any older camera or shooting mode before narrowing the fixed CCAPI v1.0 compatibility policy. Use the current production profile path and record the result by model and firmware.

Apply the collector's redaction rules to these artifacts. Record the results of physical operations separately from the read-only snapshots. The collector and the former evidence checklist are documented in [#183](https://github.com/carstenartur/Photographer/issues/183).

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

After the mock-camera bridge in [#314](https://github.com/carstenartur/Photographer/issues/314) works across both products, exercise one real paired session when the equipment is available. Record the model and firmware, lease and restoration behavior, and transfer integrity.

## Public release decisions

- Decide which first-party originals and embedded metadata may appear in the published history and downloads. Record the decision in the [publication audit](public-release.md#visibility-decision).
- Review redistribution rights and the applicable patent families for the intended distribution markets. Record the decisions in the [CR3 publication checklist](cleanroom/publication-checklist.md) and its linked review record.
- Decide whether to publish the reviewed repository history or the reviewed clean snapshot. Confirm the chosen visibility and exposed assets.

## Product decisions

Additional camera brands, multi-user/RBAC support and a dedicated hardware CI runner need a decision on intended users, hardware, compatibility, security and maintenance cost.
