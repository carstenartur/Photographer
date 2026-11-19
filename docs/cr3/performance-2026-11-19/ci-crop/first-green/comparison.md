## Same-runner CR3 JMH comparison

Regression threshold: **15.0%**. A build fails only when the threshold is exceeded and both JMH confidence intervals are available and no longer overlap.

Confirmed regressions: **0** · Noisy warnings: **0** · Improvements: **0**

| Benchmark | Parameters | Base | Head | Change | Verdict |
|---|---|---:|---:|---:|---|
| `parse` | `sample=sample.cr3` | 2.33285 | 2.32456 | -0.4% | **STABLE** |
| `readMetadata` | `sample=sample.cr3` | 0.0200706 | 0.0197957 | -1.4% | **STABLE** |
| `extractPreview` | `sample=sample.cr3` | 10.6872 | 10.5824 | -1.0% | **STABLE** |
| `decode` | `sample=sample.cr3` | 249.452 | 250.736 | +0.5% | **STABLE** |

`NOISY_WARNING` means the point estimate exceeded the threshold, but confidence data was missing or the intervals still overlap.
