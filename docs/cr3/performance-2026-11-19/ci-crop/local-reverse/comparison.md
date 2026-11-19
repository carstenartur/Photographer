## Same-runner CR3 JMH comparison

Regression threshold: **15.0%**. A build fails only when the threshold is exceeded and both JMH confidence intervals are available and no longer overlap.

Confirmed regressions: **0** · Noisy warnings: **0** · Improvements: **0**

| Benchmark | Parameters | Base | Head | Change | Verdict |
|---|---|---:|---:|---:|---|
| `decode` | `sample=sample.cr3` | 321.17 | 332.948 | +3.7% | **STABLE** |

`NOISY_WARNING` means the point estimate exceeded the threshold, but confidence data was missing or the intervals still overlap.
