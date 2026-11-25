# Default camera rendering and import performance

> **For agentic workers:** Use the systematic-debugging and test-driven-development workflows for the owned task; coordinate shared Maven builds with the controller.

**Goal:** Make the supported camera tone and optical corrections part of normal CR3 development, retain explicit opt-outs, reduce their measured cost, and recover the failed main/PR builds.

**Architecture:** Keep the existing Java processing pipeline, profile selection and public decode options. Optimize repeated optical computations using bounded working storage; retain RAW sensor pixels as the source. Default settings flow through ImageIO, core and ImageJ. A shared CI helper retries only transient remote dependency-resolution failures.

**Tech Stack:** Java 21 runtime with optional Java 25 backend, Maven on JDK 25, JUnit, Python workflow scripts.

**Spec:** User instruction on 25 November 2026: the essential development step must be enabled by default and optionally disabled; investigate substantial performance improvement. Continue PR #457 at `baebb9c69eadf2a36cb99c4c52e9703a291967a7`.

**Integration update:** PR #457 was merged independently while this work was in progress. Its resulting main commit, `6ef4f1a7ce20e44af63722405b39e0ccb77640a4`, has the same source tree as the preserved baseline. Continue on `fix/cr3-default-rendering` as a follow-up PR against that main; preserve the original timing executable.

## Global constraints

- Three unsigned 16-bit RGB channels remain the ImageIO default.
- Preserve explicit neutral processing, linear RGB behavior, sensor/Bayer reads, preview selection, and unsupported-profile fallbacks.
- No decoded-result cache, embedded-JPEG substitution, lower resolution or sample-specific benchmark shortcuts.
- Worker count defaults to available processors; preserve bounded-memory operation.
- Compare the original camera-rendered pipeline with the optimized one using identical files, JVM flags and workers.
- Preserve the existing implementation and reviewable history; make no unrelated changes or third-party implementation imports.

## Review focus

- ImageIO with null, ordinary or CR3-specific read parameters must select the same rendering.
- Changing depth, selecting a destination or toggling preview must preserve explicit opt-outs.
- Regions, subsampling, orientation and workspace destinations must match full rendering.
- Unknown camera/lens/style combinations must retain their established fallback behavior.
- Dependency retries must preserve genuine compilation/test failures and the final process status.

## Task 1: Build recovery and bounded dependency retry

**Files:** `scripts/run_maven_with_retry.py`, its script tests, `.github/workflows/cr3-benchmark.yml`, `.github/workflows/cr3-fiji-distribution.yml`.

- [x] Diagnose exact main and PR failures from job logs: Maven Central HTTP 429.
- [x] Rerun the failed jobs at their original revisions.
- [x] Add executable tests for transient failure then success, immediate compilation/test failure, retry exhaustion and argument/log preservation.
- [x] Implement bounded retries for remote resolution errors, refresh failed downloads on retry, and call the helper from the two affected workflow steps.
- [x] Verify script tests and inspect resulting CI.

## Task 2: Consistent defaults and explicit opt-out

**Files:** `Cr3DecodeOptions`, `CanonCr3ImageReadParam`, `CanonCr3ImageReader`, associated core/ImageIO/ImageJ tests and the ImageJ importer where needed.

- [x] Add tests proving normal full-precision reads match the existing camera-rendered path and explicit opt-outs restore neutral output.
- [x] Enable both supported stages for normal processed RAW; preserve the independent `applyLensCorrection(false)` and `applyCameraTone(false)` controls.
- [x] Retain the existing `cameraRendered()` factory as an explicit equivalent of RGB16 defaults.
- [x] Make tests for external neutral references select that neutral rendering explicitly; keep dedicated tests for the new real-file default.
- [x] Verify preview toggling, destination negotiation, linear/Bayer paths and unprofiled fallbacks.

## Task 3: Optical processing performance

**Files:** `canon-cr3-processing` optical/tone/developer implementation and focused tests.

- [x] Build and preserve the original executable and input/output hashes before production edits.
- [x] Measure and identify dominant repeated computations.
- [x] Add focused coverage for exact corrected values across layout, gain, borders and worker configurations.
- [x] Optimize general repeated work with bounded scratch storage and documented arithmetic.
- [x] Run identical before/after imports, full output hash comparisons and memory measurement on G5 X II and EOS R fixtures.

## Task 4: Integrate, document and verify

**Files:** API guides, README, camera-rendering report, import/quality probes and their tests.

- [x] Distinguish the current default, explicit neutral rendering and historical measurements in probes and documentation.
- [x] Record reproducible performance results and honest scope/limits.
- [ ] Run focused tests, canonical product verification and the affected packaging checks; record environmental limitations precisely.
- [ ] Review the integrated diff, publish the follow-up PR and verify current-head CI.
- [ ] Merge the verified PR and inspect main CI; do not label pending checks successful.

### Verification checkpoint before publication

The default/API and optical changes passed independent code reviews. The new
retry helper passed all ten subprocess tests. The optimized CR3/ImageJ/benchmark
package passed, including 165 processing tests; real-file ImageIO default and
opt-out tests passed. The standalone quality probe reproduced all four prior
renderings and confirmed that the current default equals the camera rendering.
Java 21 and Java 25 import fingerprints and all ten optical golden fixtures
remained unchanged. The detailed timing records are in the
[performance evidence](../../cr3/camera-rendering-performance-2026-11-25/README.md).

The unqualified canonical product command was attempted locally. A local
Mockito attachment limitation was resolved by loading the existing Mockito
startup agent through the environment, without modifying the repository.
The subsequent full run reported 1,869 unit cases with no failures or errors
(two existing skips), and the first 22 reactor entries succeeded. It ended at the application's Docker-dependent
integration setup: `/var/run/docker.sock` is unavailable, causing 57 setup
errors across 15 suites; 30 other integration cases passed and two were skipped.
No tests were disabled. Current-head GitHub CI must pass the same canonical
gate before integration.
