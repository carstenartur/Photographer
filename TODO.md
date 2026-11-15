# Roadmap and Open Work

For current status and acceptance criteria, see the [open GitHub issues](https://github.com/carstenartur/Photographer/issues?q=is%3Aissue%20is%3Aopen).

This file is retained as a stable pointer for old links. Git history preserves the former TODO list, including completed refactoring notes and superseded CRX hypotheses.

## Active areas

### Camera and CCAPI evidence

- [#143 — remaining CCAPI production hardening and capability evidence](https://github.com/carstenartur/Photographer/issues/143)
- [#144 — native Canon focus-bracketing hardware validation](https://github.com/carstenartur/Photographer/issues/144)
- [#182 — live-view mode selection and bounded cancellation](https://github.com/carstenartur/Photographer/issues/182)
- [#183 — redacted model/firmware capability snapshots](https://github.com/carstenartur/Photographer/issues/183)

The capability collector can export a redacted catalog for each camera mode and firmware version. Live view selects advertised Flip or Scroll operations. Validating native bracketing, collecting real catalogs, and verifying bounded Multipart/RTP capture still require camera access.

### CR3 / CRX

- [#249 — controlled spatial-code camera/JPEG/CR3 corpus](https://github.com/carstenartur/Photographer/issues/249)
- [#139 — publish supported CR3 artifacts to a remote Maven repository](https://github.com/carstenartur/Photographer/issues/139)

The D14 capture harness and two printable PDFs are available. Each PDF contains the same 48 patterns. The next experiment requires controlled, repeated CR3/JPEG captures from a physical camera. For the Maven release, acceptance includes resolving all six supported artifacts from a clean consumer.

The maintained research boundary is in [`docs/cr3/current-state.md`](docs/cr3/current-state.md). Former TODO claims about a single Rice-Golomb bug, a current-symbol K bracket, LibRaw JNI as a direct decoder solution, or immediately implementing CDF 9/7 are superseded by D13 and RG1. New entropy semantics require genuinely new causal evidence they are not an unchecked implementation task.

### Capture integrations

- [#314 — paired capture sessions for Fresnel](https://github.com/carstenartur/Photographer/issues/314)

The bridge has an interface and a mock transport. Pairing against Fresnel and resuming durable experiment sessions depend on the upstream protocol.

## Other proposals

Additional camera brands, multi-user/RBAC support and a dedicated hardware CI runner are product or operational decisions, not implied commitments. They should receive their own scoped issue only after intended users, hardware, compatibility policy, security model and maintenance cost are defined.

## Adding work

When creating or updating an issue, include the following information.

- the observed problem and current evidence
- scope and explicit non-goals
- an implementation or investigation plan
- checkout-runnable verification
- acceptance criteria
- hardware, clean-room or release constraints where relevant.

Do not add a second roadmap checklist here. Completed work is represented by merged pull requests and closed issues.
