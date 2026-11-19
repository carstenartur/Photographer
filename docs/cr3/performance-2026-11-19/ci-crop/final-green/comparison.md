## Same-runner CR3 JMH comparison

Regression threshold: **15.0%**. A build fails only when the threshold is exceeded and both JMH confidence intervals are available and no longer overlap.

Confirmed regressions: **0** · Noisy warnings: **0** · Improvements: **0**

| Benchmark | Parameters | Base | Head | Change | Verdict |
|---|---|---:|---:|---:|---|
| `parse` | `sample=sample.cr3` | 2.45097 | 2.37458 | -3.1% | **STABLE** |
| `readMetadata` | `sample=sample.cr3` | 0.0200306 | 0.0201321 | +0.5% | **STABLE** |
| `extractPreview` | `sample=sample.cr3` | 10.663 | 10.6081 | -0.5% | **STABLE** |
| `decode` | `sample=sample.cr3` | 252.905 | 247.112 | -2.3% | **STABLE** |

`NOISY_WARNING` means the point estimate exceeded the threshold, but confidence data was missing or the intervals still overlap.
