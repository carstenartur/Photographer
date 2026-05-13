# TODO

Open tasks and improvement areas for Focus Stack.

---

## Short-term

- [x] Remove stale Eclipse `.project` and `.classpath` files from submodules
      (done in PR "Refactoring quick wins")
- [x] Add `.project`, `.classpath`, `.settings/` to `.gitignore`
      (done in PR "Refactoring quick wins")
- [ ] Verify all REST endpoints are covered by OpenAPI annotations
      (Swagger UI should reflect the actual API completely)

## Medium-term

- [ ] CR3: Implement CDF 9/7 inverse wavelet transform
      (`InverseWaveletTransform` currently throws `UnsupportedOperationException`)
- [ ] CR3: Complete Rice-Golomb decoder for CRX bitstream
      (adaptive decoding and sub-band configurations are stubbed)
- [ ] Add Playwright or Cypress E2E tests for the frontend
      (`test-e2e.sh` currently runs API-level smoke tests only)
- [ ] Publish JaCoCo coverage badge from CI (badge endpoint exists but may
      need periodic data refresh)
- [ ] Consider extracting `analysis` package from `app` into its own module
      for clearer separation (non-urgent; current structure works)

## Long-term

- [ ] Support additional camera brands via `plugin-api` (Sony, Nikon)
- [ ] Add a module dependency graph diagram to architecture docs
      (auto-generated from Maven reactor or manual Mermaid diagram)
- [ ] Hardware-in-the-loop (HIL) test automation
      (currently manual; automate with a dedicated CI runner)
- [ ] Multi-user support with role-based access control
      (currently single admin user)

## Technical Risks / Open Questions

- PTP/USB backend (`camera-ptp`) is a clean-room implementation;
  compatibility with newer Canon models needs ongoing verification.
- Observer AI integration depends on external providers (Gemini);
  fallback behaviour when providers are unavailable should be hardened.
- `astro-core` stacking uses mean averaging; median stacking or sigma
  clipping would improve outlier rejection for long-exposure sequences.

---

## Refactoring backlog (from analysis 2026-05-10)

GitHub issues are currently empty (verified 2026-05-10). The items below are
the open engineering points consolidated from the most recent refactoring
analysis and PR reviews.

### P1 — Architecture

- [ ] **Decouple `plugin-api` from `ai-vision`.**
      `VisionProviderPlugin extends VisionProvider`, so the dependency is
      structural. Move `VisionProvider` / `VisionContext` (and any types they
      transitively need) into `common-api`, or split `plugin-api` into a pure
      SPI module plus a `plugin-api-vision` module. Then drop the
      `<dependency>` on `ai-vision` from `plugin-api/pom.xml`.
- [ ] **Reverse `ai-vision → observer-core` dependency.**
      `ai-vision` should not depend on `observer-core`; the observer should
      depend on the vision SPI, not the other way around. Likely requires
      pulling `VisionProviderPort` (or similar) down into `common-api`.
- [ ] **Split the `app` god-module.**
      `app` aggregates 15 internal modules and mixes JPA entities, REST
      controllers, plugin management and the AI provider registry. Extract
      entities/repositories into `storage`, plugin management into a dedicated
      module, keep `app` as the Spring Boot wiring layer.

### P1 — Performance

- [x] **Replace `BufferedImage.getRGB`/`setRGB` with `DataBuffer` direct
      access** in the hot paths:
      `astro-core/.../AstroStackingService`,
      `canon-cr3-imageio/.../BayerDemosaicer`,
      `canon-cr3-imageio/.../SrgbConverter`. Expected speed-up 5–20×; needs
      JMH benchmarks before/after under `canon-cr3-benchmark` (now behind
      the `-P benchmarks` profile).

### P2 — API & UX

- [ ] **Introduce `/api/v1/` REST versioning.**
      Coordinate with the `frontend-web` API client; current routes are
      unversioned. Frontend-breaking — bundle frontend changes in the same PR.
- [x] **Validate the default admin password at startup.**
      `FOCUSSTACK_SECURITY_ADMIN_PASSWORD` defaults to `focusstack` in dev.
      Refuse to boot with the default value when the active profile is `prod`.
      Needs a security review before implementing.

### P2 — CRX codec correctness (research status: 2026-05-13)

After 13 deep-refinement rounds the cleanroom CR3 decoder has reached
the limits of ground-truth-free hypothesis search. Current state:

- **Lossless RAW path** (`waveletLevels = 0`):
  - [x] Predictor-seed bug fixed (Update (p) of
        `samples/research/README.md`): `1 << (bd − 1)` → `0`.
        Validated by `Dark/FlatFixtureLosslessDecoderIT`. After the fix
        plane 3 of the flat fixture decodes near-correctly
        (median ≈ OB pedestal, ±1024 = 0.66).
  - [ ] **Entropy-lock-loss in `CrxLosslessRawDecoder.decodePlane`.**
        Three of four Bayer planes still saturate at 16320 in the dark
        fixture. This is the single remaining production bug — Round-9
        validated `K_INIT = 0` but the JPEG-LS K-update accumulator may
        still drift the rate parameter incorrectly under heavy zero-residual
        runs. Fix candidate: per-symbol bracket
        `K* = floor(log2(s + 1)), K ∈ [K* − 1, K* + 1]` (patent literature).
  - [ ] Tile-level parallelization. Premature; re-evaluate after the
        entropy-lock bug is fixed.

- **C-RAW path** (`waveletLevels > 0`):
  - [x] Round-12 explored predictor + sign + dequant + IDWT permutation;
        best `|Pearson r| = 0.6573` on `g5xm2-detail-…-02 / tile1/p0`.
  - [x] Round-13 deep refinement (266 064 variants, 2:22 h, four passes:
        per-level role permutations, per-band negation, per-role
        predictor): **no improvement over Round-12**.
        Documented as a research dead-end in `samples/research/README.md`
        Update (o). Round-13 IT is `@Disabled` in CI to keep the
        pipeline fast (opt-in instructions in the IT class JavaDoc).
  - [ ] **LibRaw-backed JNI ground truth** — the next concrete research
        milestone. Promote the existing G5X II C-RAW fixtures to
        pixel-exact regression tests by pinning expected per-plane
        output against LibRaw's decoder. Without ground truth the
        hypothesis-space sweeps cannot make further progress.
  - [ ] CDF 9/7 inverse wavelet implementation
        (`InverseWaveletTransform` currently throws
        `UnsupportedOperationException` for the 9/7 filter). No
        observed fixture requires it yet, but the patent mentions it.

- **Format documentation:**
  - [x] `canon-cr3-imageio/CR3_FORMAT.md` — concise standalone format
        reference card (created 2026-05-13). Independent of any
        copyrighted Canon material.
  - [x] `canon-cr3-core/SubbandFixtures.md` — running ledger of every
        fixture's CMP1 + per-subband layout (1399 lines).
  - [x] `samples/research/README.md` — updates (a)–(p) cover the
        capture workflow, the cleanroom analysis, and every production
        change.
  - [x] `BACKUP.md` (root) — recovery guide listing what is in git vs.
        what must be backed up privately.

### P3 — Hygiene & polish

- [ ] **Add `@PreDestroy` hook on `ReplayService` executor** so the bounded
      thread pool is shut down cleanly on Spring context close. (Today it
      relies on daemon threads being killed at JVM exit, which is fine for
      `main` but noisy in integration tests.)
- [ ] **Reduce `frontend-web` `index` chunk size.**
      Largest remaining bundle after route-splitting is ~190 kB
      (`StackGallery` page is 100 kB on its own). Investigate dynamic-import
      for the gallery's heavier dependencies.
