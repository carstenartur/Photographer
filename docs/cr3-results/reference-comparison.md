# CR3 Reference Comparison

[Original report with embedded images](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/reference-comparison.md) · [All original figures](https://github.com/carstenartur/Photographer/tree/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison)

- Generated: 2026-06-14T19:31:23.054742Z
- Fixtures: 11 lossless CR3 fixtures
- Exclusions: C-RAW / wavelet CR3 fixtures are intentionally skipped
- Reference manifest: `canon-cr3-imageio/src/test/resources/samples/reference-manifest.json`
- Reference source: LibRaw 0.22.1 via rawpy 0.27.0 full-sensor mosaic TIFF sidecars

## Summary

| Fixture | Size | Global MAE | Global RMSE | Pearson | OB MAE | OB StdDev | Boundary Error |
|---|---:|---:|---:|---:|---:|---:|---:|
| `eosr-iso-high.cr3` | 6888×4546 | 1735.1697 | 1769.8080 | 0.2208 | 1967.8177 | 65.7684 | 1786.9294 |
| `eosr-iso-low.cr3` | 6888×4546 | 1691.6085 | 1731.1533 | 0.2799 | 1942.1394 | 88.0079 | 1761.0366 |
| `eosr-iso-mid.cr3` | 6888×4546 | 1733.2698 | 1769.7069 | 0.2167 | 1966.0290 | 69.3977 | 1806.9856 |
| `research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3` | 5632×3710 | 1706.9891 | 2162.8831 | -0.2461 | 1819.9539 | 236.4949 | 2365.6625 |
| `research/g5xm2-dark-iso125-ss160-ff7p1-20260504-192532-02.cr3` | 5632×3710 | 1365.1349 | 1438.1309 | -0.0093 | 1733.2005 | 265.0815 | 1438.5687 |
| `research/g5xm2-flat-iso1600-ss0p4s-ff1p6-20260504-193022-02.cr3` | 5632×3710 | 14955.9089 | 15461.7053 | 0.0455 | 5454.8257 | 6110.9319 | 16097.5910 |
| `sample-iso-high.cr3` | 5632×3710 | 13294.9533 | 13937.7830 | 0.0662 | 3451.1305 | 3848.7242 | 14238.0074 |
| `sample-tele.cr3` | 5632×3710 | 2983.7212 | 3290.8050 | -0.4176 | 2181.4054 | 733.3228 | 3192.8829 |
| `sample-wb-tungsten.cr3` | 5632×3710 | 13364.9529 | 14009.7197 | -0.0698 | 3473.7355 | 3863.1556 | 14243.2892 |
| `sample-wide.cr3` | 5632×3710 | 2987.3870 | 3285.1004 | -0.2891 | 2167.5443 | 741.2664 | 3164.2027 |
| `sample.cr3` | 5632×3710 | 1833.9213 | 1930.1354 | -0.1162 | 1936.2878 | 222.6886 | 2028.8067 |

## Per fixture

### `eosr-iso-high.cr3`

| Scope | MAE | RMSE | Pearson |
|---|---:|---:|---:|
| Global | 1735.1697 | 1769.8080 | 0.2208 |
| Plane R | 1605.4249 | 1645.0989 | — |
| Plane Gr | 1804.3062 | 1837.0424 | — |
| Plane Gb | 1824.3860 | 1856.5440 | — |
| Plane B | 1706.5616 | 1732.3539 | — |
| Optical Black | 1967.8177 | 65.7684 | — |
| Tile Boundaries | 1786.9294 | — | — |

| Preview | Reference | Decoder | Difference |
|---|---|---|---|
| [Preview](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/eosr-iso-high-preview.png) | [Reference](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/eosr-iso-high-reference.png) | [Decoder](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/eosr-iso-high-decoder.png) | [Difference](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/eosr-iso-high-difference.png) |

### `eosr-iso-low.cr3`

| Scope | MAE | RMSE | Pearson |
|---|---:|---:|---:|
| Global | 1691.6085 | 1731.1533 | 0.2799 |
| Plane R | 1626.6059 | 1673.8292 | — |
| Plane Gr | 1752.2518 | 1786.5949 | — |
| Plane Gb | 1716.5728 | 1759.1025 | — |
| Plane B | 1671.0036 | 1702.7916 | — |
| Optical Black | 1942.1394 | 88.0079 | — |
| Tile Boundaries | 1761.0366 | — | — |

| Preview | Reference | Decoder | Difference |
|---|---|---|---|
| [Preview](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/eosr-iso-low-preview.png) | [Reference](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/eosr-iso-low-reference.png) | [Decoder](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/eosr-iso-low-decoder.png) | [Difference](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/eosr-iso-low-difference.png) |

### `eosr-iso-mid.cr3`

| Scope | MAE | RMSE | Pearson |
|---|---:|---:|---:|
| Global | 1733.2698 | 1769.7069 | 0.2167 |
| Plane R | 1653.3339 | 1686.7162 | — |
| Plane Gr | 1825.8883 | 1857.6482 | — |
| Plane Gb | 1760.6275 | 1803.2078 | — |
| Plane B | 1693.2296 | 1726.2744 | — |
| Optical Black | 1966.0290 | 69.3977 | — |
| Tile Boundaries | 1806.9856 | — | — |

| Preview | Reference | Decoder | Difference |
|---|---|---|---|
| [Preview](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/eosr-iso-mid-preview.png) | [Reference](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/eosr-iso-mid-reference.png) | [Decoder](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/eosr-iso-mid-decoder.png) | [Difference](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/eosr-iso-mid-difference.png) |

### `research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3`

| Scope | MAE | RMSE | Pearson |
|---|---:|---:|---:|
| Global | 1706.9891 | 2162.8831 | -0.2461 |
| Plane R | 1270.0725 | 1384.0657 | — |
| Plane Gr | 1563.7374 | 1675.7751 | — |
| Plane Gb | 1708.8536 | 2461.9413 | — |
| Plane B | 2285.2931 | 2815.5351 | — |
| Optical Black | 1819.9539 | 236.4949 | — |
| Tile Boundaries | 2365.6625 | — | — |

| Preview | Reference | Decoder | Difference |
|---|---|---|---|
| [Preview](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/g5xm2-asis-iso200-ss130-ff2p5-01-preview.png) | [Reference](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/g5xm2-asis-iso200-ss130-ff2p5-01-reference.png) | [Decoder](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/g5xm2-asis-iso200-ss130-ff2p5-01-decoder.png) | [Difference](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/g5xm2-asis-iso200-ss130-ff2p5-01-difference.png) |

### `research/g5xm2-dark-iso125-ss160-ff7p1-20260504-192532-02.cr3`

| Scope | MAE | RMSE | Pearson |
|---|---:|---:|---:|
| Global | 1365.1349 | 1438.1309 | -0.0093 |
| Plane R | 1264.7230 | 1368.2022 | — |
| Plane Gr | 1346.9670 | 1408.6275 | — |
| Plane Gb | 1459.9755 | 1526.4376 | — |
| Plane B | 1388.8741 | 1444.5284 | — |
| Optical Black | 1733.2005 | 265.0815 | — |
| Tile Boundaries | 1438.5687 | — | — |

| Preview | Reference | Decoder | Difference |
|---|---|---|---|
| [Preview](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/g5xm2-dark-iso125-ss160-ff7p1-20260504-192532-02-preview.png) | [Reference](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/g5xm2-dark-iso125-ss160-ff7p1-20260504-192532-02-reference.png) | [Decoder](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/g5xm2-dark-iso125-ss160-ff7p1-20260504-192532-02-decoder.png) | [Difference](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/g5xm2-dark-iso125-ss160-ff7p1-20260504-192532-02-difference.png) |

### `research/g5xm2-flat-iso1600-ss0p4s-ff1p6-20260504-193022-02.cr3`

| Scope | MAE | RMSE | Pearson |
|---|---:|---:|---:|
| Global | 14955.9089 | 15461.7053 | 0.0455 |
| Plane R | 12586.1101 | 14105.7243 | — |
| Plane Gr | 15851.8223 | 15987.9044 | — |
| Plane Gb | 15611.3377 | 15771.0023 | — |
| Plane B | 15774.3657 | 15904.3475 | — |
| Optical Black | 5454.8257 | 6110.9319 | — |
| Tile Boundaries | 16097.5910 | — | — |

| Preview | Reference | Decoder | Difference |
|---|---|---|---|
| [Preview](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/g5xm2-flat-iso1600-ss0p4s-ff1p6-20260504-193022-02-preview.png) | [Reference](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/g5xm2-flat-iso1600-ss0p4s-ff1p6-20260504-193022-02-reference.png) | [Decoder](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/g5xm2-flat-iso1600-ss0p4s-ff1p6-20260504-193022-02-decoder.png) | [Difference](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/g5xm2-flat-iso1600-ss0p4s-ff1p6-20260504-193022-02-difference.png) |

### `sample-iso-high.cr3`

| Scope | MAE | RMSE | Pearson |
|---|---:|---:|---:|
| Global | 13294.9533 | 13937.7830 | 0.0662 |
| Plane R | 14379.2004 | 14833.7870 | — |
| Plane Gr | 14836.7917 | 15211.0968 | — |
| Plane Gb | 14682.8835 | 15057.4123 | — |
| Plane B | 9280.9376 | 9944.9892 | — |
| Optical Black | 3451.1305 | 3848.7242 | — |
| Tile Boundaries | 14238.0074 | — | — |

| Preview | Reference | Decoder | Difference |
|---|---|---|---|
| [Preview](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/sample-iso-high-preview.png) | [Reference](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/sample-iso-high-reference.png) | [Decoder](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/sample-iso-high-decoder.png) | [Difference](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/sample-iso-high-difference.png) |

### `sample-tele.cr3`

| Scope | MAE | RMSE | Pearson |
|---|---:|---:|---:|
| Global | 2983.7212 | 3290.8050 | -0.4176 |
| Plane R | 3064.9098 | 3212.3865 | — |
| Plane Gr | 3724.3454 | 3895.4192 | — |
| Plane Gb | 3725.6190 | 3900.8231 | — |
| Plane B | 1420.0106 | 1614.7603 | — |
| Optical Black | 2181.4054 | 733.3228 | — |
| Tile Boundaries | 3192.8829 | — | — |

| Preview | Reference | Decoder | Difference |
|---|---|---|---|
| [Preview](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/sample-tele-preview.png) | [Reference](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/sample-tele-reference.png) | [Decoder](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/sample-tele-decoder.png) | [Difference](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/sample-tele-difference.png) |

### `sample-wb-tungsten.cr3`

| Scope | MAE | RMSE | Pearson |
|---|---:|---:|---:|
| Global | 13364.9529 | 14009.7197 | -0.0698 |
| Plane R | 14389.1505 | 14829.2055 | — |
| Plane Gr | 14894.0863 | 15263.8191 | — |
| Plane Gb | 14888.7271 | 15255.6677 | — |
| Plane B | 9287.8478 | 9973.1680 | — |
| Optical Black | 3473.7355 | 3863.1556 | — |
| Tile Boundaries | 14243.2892 | — | — |

| Preview | Reference | Decoder | Difference |
|---|---|---|---|
| [Preview](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/sample-wb-tungsten-preview.png) | [Reference](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/sample-wb-tungsten-reference.png) | [Decoder](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/sample-wb-tungsten-decoder.png) | [Difference](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/sample-wb-tungsten-difference.png) |

### `sample-wide.cr3`

| Scope | MAE | RMSE | Pearson |
|---|---:|---:|---:|
| Global | 2987.3870 | 3285.1004 | -0.2891 |
| Plane R | 3140.6703 | 3262.0319 | — |
| Plane Gr | 3591.2516 | 3761.4078 | — |
| Plane Gb | 3565.6443 | 3775.3095 | — |
| Plane B | 1651.9818 | 2031.1415 | — |
| Optical Black | 2167.5443 | 741.2664 | — |
| Tile Boundaries | 3164.2027 | — | — |

| Preview | Reference | Decoder | Difference |
|---|---|---|---|
| [Preview](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/sample-wide-preview.png) | [Reference](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/sample-wide-reference.png) | [Decoder](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/sample-wide-decoder.png) | [Difference](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/sample-wide-difference.png) |

### `sample.cr3`

| Scope | MAE | RMSE | Pearson |
|---|---:|---:|---:|
| Global | 1833.9213 | 1930.1354 | -0.1162 |
| Plane R | 1694.5870 | 1754.5989 | — |
| Plane Gr | 2082.4102 | 2134.9070 | — |
| Plane Gb | 2103.8570 | 2145.7514 | — |
| Plane B | 1454.8310 | 1631.2562 | — |
| Optical Black | 1936.2878 | 222.6886 | — |
| Tile Boundaries | 2028.8067 | — | — |

| Preview | Reference | Decoder | Difference |
|---|---|---|---|
| [Preview](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/sample-preview.png) | [Reference](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/sample-reference.png) | [Decoder](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/sample-decoder.png) | [Difference](https://github.com/carstenartur/Photographer/blob/271db071539de2a0a2e4f5cdbeaeb432af65fba1/docs/cr3-results/images/reference-comparison/sample-difference.png) |
