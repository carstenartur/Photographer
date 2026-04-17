# TODO

Open tasks and improvement areas for Focus Stack.

---

## Short-term

- [ ] Remove stale Eclipse `.project` and `.classpath` files from submodules
      (they are committed but not in `.gitignore`; low risk but adds clutter)
- [ ] Add `.project`, `.classpath`, `.settings/` to `.gitignore`
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
