# Roadmap and Open Work

Current work is tracked in [GitHub Issues](https://github.com/carstenartur/Photographer/issues), not in Markdown checklists.

This file is retained as a stable pointer for old links. Git history preserves the former TODO list, including completed refactoring notes and superseded CRX hypotheses.

## Active areas

### Camera and CCAPI evidence

- [#143 — remaining CCAPI production hardening and capability evidence](https://github.com/carstenartur/Photographer/issues/143)
- [#144 — native Canon focus-bracketing hardware validation](https://github.com/carstenartur/Photographer/issues/144)
- [#182 — live-view mode selection and bounded cancellation](https://github.com/carstenartur/Photographer/issues/182)
- [#183 — redacted model/firmware capability snapshots](https://github.com/carstenartur/Photographer/issues/183)

The bounded Scroll fallback is implemented; Multipart/RTP and exact model/firmware claims remain evidence-driven work.

### CR3 / CRX

- [#249 — controlled spatial-code camera/JPEG/CR3 corpus](https://github.com/carstenartur/Photographer/issues/249)
- [#139 — publish supported CR3 artifacts to a remote Maven repository](https://github.com/carstenartur/Photographer/issues/139)
- [#179 — benchmark variance calibration and regression sentinel](https://github.com/carstenartur/Photographer/issues/179)

The maintained research boundary is in [`docs/cr3/current-state.md`](docs/cr3/current-state.md). Former TODO claims about a single Rice-Golomb bug, a current-symbol K bracket, LibRaw JNI as a direct decoder solution, or immediately implementing CDF 9/7 are superseded by D13 and RG1. New entropy semantics require genuinely new causal evidence; they are not an unchecked implementation task.

### Architecture, quality and resilience

- [#282 — correct vision SPI and module dependency direction](https://github.com/carstenartur/Photographer/issues/282)
- [#283 — real-browser E2E coverage](https://github.com/carstenartur/Photographer/issues/283)
- [#284 — explicit REST API versioning](https://github.com/carstenartur/Photographer/issues/284)
- [#285 — bounded AI-provider outage/fallback behavior](https://github.com/carstenartur/Photographer/issues/285)
- [#286 — robust astronomical stacking and outlier rejection](https://github.com/carstenartur/Photographer/issues/286)

### Documentation quality

- [#279 — generated CR3 evidence ownership and verify/update contract](https://github.com/carstenartur/Photographer/issues/279)
- [#281 — Markdown links and canonical documentation ownership enforcement](https://github.com/carstenartur/Photographer/issues/281)

## Deliberately not committed roadmap items

Additional camera brands, multi-user/RBAC support and a dedicated hardware CI runner are product or operational decisions, not implied commitments. They should receive their own scoped issue only after intended users, hardware, compatibility policy, security model and maintenance cost are defined.

## Adding work

Create or update a GitHub issue with:

- the observed problem and current evidence;
- scope and explicit non-goals;
- an implementation or investigation plan;
- checkout-runnable verification;
- acceptance criteria;
- hardware, clean-room or release constraints where relevant.

Do not add a second roadmap checklist here. Completed work is represented by merged pull requests and closed issues.