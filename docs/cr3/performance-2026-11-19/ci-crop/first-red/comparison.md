## Same-runner CR3 JMH comparison

Regression threshold: **15.0%**. A build fails only when the threshold is exceeded and both JMH confidence intervals are available and no longer overlap.

Confirmed regressions: **1** · Noisy warnings: **0** · Improvements: **0**

| Benchmark | Parameters | Base | Head | Change | Verdict |
|---|---|---:|---:|---:|---|
| `parse` | `sample=sample.cr3` | 2.36342 | 2.43618 | +3.1% | **STABLE** |
| `readMetadata` | `sample=sample.cr3` | 0.0243427 | 0.0239353 | -1.7% | **STABLE** |
| `extractPreview` | `sample=sample.cr3` | 16.2943 | 16.194 | -0.6% | **STABLE** |
| `decode` | `sample=sample.cr3` | 245.778 | 317.327 | +29.1% | **REGRESSION** |

`NOISY_WARNING` means the point estimate exceeded the threshold, but confidence data was missing or the intervals still overlap.
