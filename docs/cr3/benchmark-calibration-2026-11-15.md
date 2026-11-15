# CR3 sentinel calibration, 15 November 2026

Four PR sentinels measured the same `sample.cr3` at the same revision
(`0d94d6c5`). Each base/head pair ran sequentially. Each JVM used two
one-second warm-ups and three one-second measurement iterations. The host
was shared. The table reports signed head/base change in mean time. Pair
time includes JMH startup and both measurements, excluding the single shared
Maven build (9.1 s). These are local seconds. GitHub runner timings and
environment information are collected independently by the calibration job.

| Forks | Order | Parse | Metadata | Preview | Lossless decode | Pair time |
|---:|---|---:|---:|---:|---:|---:|
| 1 | Base first | +6.3% | −2.4% | +5.0% | +0.9% | 46.2 s |
| 1 | Head first | +1.5% | −2.8% | −7.8% | +5.1% | 46.0 s |
| 2 | Base first | +2.3% | −10.5% | +14.3% | −1.8% | 92.0 s |
| 2 | Head first | +4.3% | −18.2% | +20.9% | −3.1% | 90.2 s |
| 3 | Base first | −2.3% | +3.9% | +0.4% | +6.3% | 136.0 s |
| 3 | Head first | −0.4% | −22.8% | +10.4% | −7.6% | 138.4 s |

The comparator classified 23 of 24 tuples as stable. The +20.9% preview
measurement was a noisy warning because its confidence intervals overlapped.
No unchanged-code comparison failed the existing 15% and disjoint-interval
rule. More forks increased cost and still produced order-sensitive results.
One matrix provides insufficient evidence to narrow or widen that threshold.

Keep one fork for the normal PR gate. A noisy threshold crossing produces
a warning for manual inspection. A confirmed regression fails the PR and
requires a reverse-order rerun for attribution. There is no automatic second
run on every warning. The deterministic two-ref gate test injects one slower
parameter and one stable parameter, checks the exit status and verifies the
same JSON summary consumed by CI.

The raw records include every iteration and the JMH confidence intervals.
Use the [raw JMH directory](benchmark-calibration-2026-11-15/) and
[environment and timings](benchmark-calibration-2026-11-15/environment.txt)
to reproduce the table. [Runner and comparison instructions](../cr3-benchmarking.md).

## GitHub-hosted runner

[Workflow 43700326062](https://github.com/carstenartur/Photographer/actions/runs/43700326062)
repeated the matrix on one two-vCPU Ubuntu runner. Each base and head used
the identical PR revision `3dd84bde`. The one-time Maven build took 26.2 s.
Pair elapsed time includes both JMH processes. The 12 measured processes
used 532.4 s in total, excluding checkout, setup and artifact handling.

| Forks | Order | Parse | Metadata | Preview | Lossless decode | Pair time |
|---:|---|---:|---:|---:|---:|---:|
| 1 | Base first | +12.2% | 0.0% | −0.7% | +1.4% | 46.2 s |
| 1 | Head first | +3.7% | −3.0% | +0.5% | −0.8% | 44.5 s |
| 2 | Base first | +1.2% | +1.0% | −0.6% | −0.6% | 87.9 s |
| 2 | Head first | −7.5% | −2.3% | −0.2% | −0.6% | 89.5 s |
| 3 | Base first | +0.1% | −1.6% | +0.1% | +1.3% | 132.8 s |
| 3 | Head first | +6.4% | −2.6% | +0.5% | −0.4% | 131.5 s |

All 24 hosted tuples were stable. For this runner, three forks cost about
three times as much as one. Retaining one fork with the 15% threshold and
the confidence-interval check is consistent with both matrices. A future
runner image or JDK can be recalibrated using the same workflow.
[Raw hosted measurements and timings](benchmark-calibration-2026-11-15/hosted-jmh.json) ·
[Runner and toolchain](benchmark-calibration-2026-11-15/hosted-environment.txt).
