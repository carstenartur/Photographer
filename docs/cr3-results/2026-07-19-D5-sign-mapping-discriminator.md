# D5 Sign-Mapping Discriminator

## Hypothesis and goal

- Hypothesis: **D5** — residual/value semantics are wrong while the neighbourhood family is roughly MED-like.
- Goal: isolate only unsigned-to-signed residual mapping and determine whether conventional zig-zag mapping is sufficiently supported to replace the current bit-0 sign mapping.
- Execution: GitHub Actions run `29680082036`, commit `2655b984864d1b7711fc3a19d3237b96beefc87c`, generated `2026-07-19T08:36:38.425837818Z`.

Only the unsigned-to-signed residual mapping changes. Unary direction, K initialization, row reset, K adaptation, escape behavior, MED predictor, seed and assembly are identical.

## Decision rule

A production change requires all of the following:

1. at least two of the three discriminator fixtures (`sample-tele`, `sample-wide`, `research/g5xm2-asis-iso200-ss130-ff2p5-01`) flip Pearson correlation from negative to positive;
2. global MAE and optical-black MAE improve on those successful discriminator fixtures;
3. median full-corpus error does not regress;
4. decode completion does not regress.

## Verdict: **REJECTED**

- successful discriminator fixtures: 0 / 3
- Pearson sign flips: 0 / 3
- median global MAE BIT0/ZIGZAG: 1833.9213 / 2315.9968
- median boundary error BIT0/ZIGZAG: 2365.6625 / 3007.1074
- no completion regression: false

Conventional zig-zag residual sign mapping is therefore not supported as an isolated production decoder change. It generally increases global, optical-black and boundary error, and it reduces completion on several already incomplete fixtures. The result does not prove that the existing bit-0 mapping is correct; it only falsifies this one-variable replacement under the otherwise fixed candidate decoder.

## Fixture metrics

| Fixture | Mapping | MAE | RMSE | Pearson | OB MAE | Boundary | Completion | Exhausted |
|---|---|---:|---:|---:|---:|---:|---:|:---:|
| `eosr-iso-high.cr3` | BIT0_SIGN | 1735.1697 | 1769.8080 | 0.2208 | 1967.8177 | 1786.9294 | 1.0000 | no |
| `eosr-iso-high.cr3` | ZIGZAG_SIGN | 2248.8065 | 2259.1171 | 0.0101 | 2042.9978 | 2229.9015 | 1.0000 | no |
| `eosr-iso-low.cr3` | BIT0_SIGN | 1691.6085 | 1731.1533 | 0.2799 | 1942.1394 | 1761.0366 | 1.0000 | no |
| `eosr-iso-low.cr3` | ZIGZAG_SIGN | 2250.2706 | 2261.4401 | -0.0535 | 2041.7747 | 2219.2844 | 1.0000 | no |
| `eosr-iso-mid.cr3` | BIT0_SIGN | 1733.2698 | 1769.7069 | 0.2167 | 1966.0290 | 1806.9856 | 1.0000 | no |
| `eosr-iso-mid.cr3` | ZIGZAG_SIGN | 2207.5203 | 2227.1137 | 0.0580 | 2040.8223 | 2233.9456 | 1.0000 | no |
| `research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3` | BIT0_SIGN | 1706.9891 | 2162.8831 | -0.2461 | 1819.9539 | 2365.6625 | 1.0000 | no |
| `research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3` | ZIGZAG_SIGN | 1687.3025 | 2161.6381 | -0.0661 | 2017.9101 | 1764.8410 | 1.0000 | no |
| `research/g5xm2-dark-iso125-ss160-ff7p1-20260504-192532-02.cr3` | BIT0_SIGN | 1365.1349 | 1438.1309 | -0.0093 | 1733.2005 | 1438.5687 | 1.0000 | no |
| `research/g5xm2-dark-iso125-ss160-ff7p1-20260504-192532-02.cr3` | ZIGZAG_SIGN | 1973.2743 | 1977.8491 | 0.0018 | 1953.4126 | 1925.2520 | 1.0000 | no |
| `research/g5xm2-flat-iso1600-ss0p4s-ff1p6-20260504-193022-02.cr3` | BIT0_SIGN | 9221.8301 | 11681.5552 | 0.1382 | 5356.0063 | 8154.8819 | 0.0829 | yes |
| `research/g5xm2-flat-iso1600-ss0p4s-ff1p6-20260504-193022-02.cr3` | ZIGZAG_SIGN | 10015.4031 | 12758.3368 | 0.1132 | 5474.0879 | 8247.7345 | 0.0761 | yes |
| `sample-iso-high.cr3` | BIT0_SIGN | 13296.6568 | 13939.1228 | 0.0690 | 3430.9171 | 14240.9724 | 0.9319 | yes |
| `sample-iso-high.cr3` | ZIGZAG_SIGN | 13700.1047 | 14344.7163 | -0.2018 | 3537.6002 | 14565.6451 | 0.8915 | yes |
| `sample-tele.cr3` | BIT0_SIGN | 2983.7212 | 3290.8050 | -0.4176 | 2181.4054 | 3192.8829 | 1.0000 | no |
| `sample-tele.cr3` | ZIGZAG_SIGN | 3518.9784 | 3712.3535 | -0.2738 | 2253.9759 | 3416.9806 | 1.0000 | no |
| `sample-wb-tungsten.cr3` | BIT0_SIGN | 13360.0214 | 14005.5918 | -0.0759 | 3435.2810 | 14239.3399 | 0.9121 | yes |
| `sample-wb-tungsten.cr3` | ZIGZAG_SIGN | 13636.7709 | 14295.7921 | -0.1028 | 3540.8291 | 14634.7163 | 0.8709 | yes |
| `sample-wide.cr3` | BIT0_SIGN | 2987.3870 | 3285.1004 | -0.2891 | 2167.5443 | 3164.2027 | 1.0000 | no |
| `sample-wide.cr3` | ZIGZAG_SIGN | 3495.9167 | 3685.1837 | -0.1847 | 2248.1309 | 3436.2856 | 1.0000 | no |
| `sample.cr3` | BIT0_SIGN | 1833.9213 | 1930.1354 | -0.1162 | 1936.2878 | 2028.8067 | 1.0000 | no |
| `sample.cr3` | ZIGZAG_SIGN | 2315.9968 | 2440.8233 | -0.0399 | 2069.6823 | 3007.1074 | 1.0000 | no |

## Per-plane metrics

| Fixture | Mapping | Plane | MAE | RMSE | Pearson |
|---|---|---:|---:|---:|---:|
| `eosr-iso-high.cr3` | BIT0_SIGN | 0 | 1605.4249 | 1645.0989 | 0.2649 |
| `eosr-iso-high.cr3` | BIT0_SIGN | 1 | 1804.3062 | 1837.0424 | 0.2553 |
| `eosr-iso-high.cr3` | BIT0_SIGN | 2 | 1824.3860 | 1856.5440 | 0.2620 |
| `eosr-iso-high.cr3` | BIT0_SIGN | 3 | 1706.5616 | 1732.3539 | 0.2611 |
| `eosr-iso-high.cr3` | ZIGZAG_SIGN | 0 | 2176.1453 | 2182.0420 | -0.0709 |
| `eosr-iso-high.cr3` | ZIGZAG_SIGN | 1 | 2321.3775 | 2333.9568 | -0.0682 |
| `eosr-iso-high.cr3` | ZIGZAG_SIGN | 2 | 2298.2450 | 2311.6460 | 0.0571 |
| `eosr-iso-high.cr3` | ZIGZAG_SIGN | 3 | 2199.4583 | 2205.0103 | -0.0189 |
| `eosr-iso-low.cr3` | BIT0_SIGN | 0 | 1626.6059 | 1673.8292 | 0.2348 |
| `eosr-iso-low.cr3` | BIT0_SIGN | 1 | 1752.2518 | 1786.5949 | 0.3298 |
| `eosr-iso-low.cr3` | BIT0_SIGN | 2 | 1716.5728 | 1759.1025 | 0.2965 |
| `eosr-iso-low.cr3` | BIT0_SIGN | 3 | 1671.0036 | 1702.7916 | 0.2561 |
| `eosr-iso-low.cr3` | ZIGZAG_SIGN | 0 | 2181.5623 | 2187.1241 | -0.0242 |
| `eosr-iso-low.cr3` | ZIGZAG_SIGN | 1 | 2316.9557 | 2330.5588 | -0.0411 |
| `eosr-iso-low.cr3` | ZIGZAG_SIGN | 2 | 2316.8288 | 2330.1754 | -0.0330 |
| `eosr-iso-low.cr3` | ZIGZAG_SIGN | 3 | 2185.7355 | 2193.5613 | -0.0797 |
| `eosr-iso-mid.cr3` | BIT0_SIGN | 0 | 1653.3339 | 1686.7162 | 0.2051 |
| `eosr-iso-mid.cr3` | BIT0_SIGN | 1 | 1825.8883 | 1857.6482 | 0.2529 |
| `eosr-iso-mid.cr3` | BIT0_SIGN | 2 | 1760.6275 | 1803.2078 | 0.2523 |
| `eosr-iso-mid.cr3` | BIT0_SIGN | 3 | 1693.2296 | 1726.2744 | 0.1870 |
| `eosr-iso-mid.cr3` | ZIGZAG_SIGN | 0 | 2169.9448 | 2176.0813 | 0.0135 |
| `eosr-iso-mid.cr3` | ZIGZAG_SIGN | 1 | 2266.8931 | 2291.6732 | -0.0776 |
| `eosr-iso-mid.cr3` | ZIGZAG_SIGN | 2 | 2213.7189 | 2250.0853 | 0.0892 |
| `eosr-iso-mid.cr3` | ZIGZAG_SIGN | 3 | 2179.5246 | 2188.6438 | -0.1147 |
| `research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3` | BIT0_SIGN | 0 | 1270.0725 | 1384.0657 | -0.2618 |
| `research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3` | BIT0_SIGN | 1 | 1563.7374 | 1675.7751 | -0.3181 |
| `research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3` | BIT0_SIGN | 2 | 1708.8536 | 2461.9413 | -0.2633 |
| `research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3` | BIT0_SIGN | 3 | 2285.2931 | 2815.5351 | -0.1418 |
| `research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3` | ZIGZAG_SIGN | 0 | 2127.2466 | 2959.4850 | -0.1466 |
| `research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3` | ZIGZAG_SIGN | 1 | 1530.3863 | 1714.9695 | -0.0854 |
| `research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3` | ZIGZAG_SIGN | 2 | 1618.3565 | 1985.0131 | 0.2367 |
| `research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3` | ZIGZAG_SIGN | 3 | 1473.2205 | 1746.6446 | -0.2163 |
| `research/g5xm2-dark-iso125-ss160-ff7p1-20260504-192532-02.cr3` | BIT0_SIGN | 0 | 1264.7230 | 1368.2022 | -0.0131 |
| `research/g5xm2-dark-iso125-ss160-ff7p1-20260504-192532-02.cr3` | BIT0_SIGN | 1 | 1346.9670 | 1408.6275 | -0.0113 |
| `research/g5xm2-dark-iso125-ss160-ff7p1-20260504-192532-02.cr3` | BIT0_SIGN | 2 | 1459.9755 | 1526.4376 | -0.0104 |
| `research/g5xm2-dark-iso125-ss160-ff7p1-20260504-192532-02.cr3` | BIT0_SIGN | 3 | 1388.8741 | 1444.5284 | -0.0147 |
| `research/g5xm2-dark-iso125-ss160-ff7p1-20260504-192532-02.cr3` | ZIGZAG_SIGN | 0 | 1988.8036 | 1992.2198 | -0.0044 |
| `research/g5xm2-dark-iso125-ss160-ff7p1-20260504-192532-02.cr3` | ZIGZAG_SIGN | 1 | 1986.7338 | 1989.1259 | 0.0051 |
| `research/g5xm2-dark-iso125-ss160-ff7p1-20260504-192532-02.cr3` | ZIGZAG_SIGN | 2 | 1989.7022 | 1992.7938 | 0.0023 |
| `research/g5xm2-dark-iso125-ss160-ff7p1-20260504-192532-02.cr3` | ZIGZAG_SIGN | 3 | 1927.8577 | 1936.6878 | 0.0032 |
| `research/g5xm2-flat-iso1600-ss0p4s-ff1p6-20260504-193022-02.cr3` | BIT0_SIGN | 0 | 8039.0636 | 11429.1902 | 0.1462 |
| `research/g5xm2-flat-iso1600-ss0p4s-ff1p6-20260504-193022-02.cr3` | BIT0_SIGN | 1 | 11864.4310 | 12579.9444 | 0.1480 |
| `research/g5xm2-flat-iso1600-ss0p4s-ff1p6-20260504-193022-02.cr3` | BIT0_SIGN | 2 | 7925.8280 | 11269.2176 | 0.1456 |
| `research/g5xm2-flat-iso1600-ss0p4s-ff1p6-20260504-193022-02.cr3` | BIT0_SIGN | 3 | 9057.9980 | 11399.9242 | 0.1452 |
| `research/g5xm2-flat-iso1600-ss0p4s-ff1p6-20260504-193022-02.cr3` | ZIGZAG_SIGN | 0 | 8054.2124 | 11454.6803 | 0.1461 |
| `research/g5xm2-flat-iso1600-ss0p4s-ff1p6-20260504-193022-02.cr3` | ZIGZAG_SIGN | 1 | 8013.0997 | 11411.1530 | 0.1461 |
| `research/g5xm2-flat-iso1600-ss0p4s-ff1p6-20260504-193022-02.cr3` | ZIGZAG_SIGN | 2 | 8021.5940 | 11416.0590 | 0.1447 |
| `research/g5xm2-flat-iso1600-ss0p4s-ff1p6-20260504-193022-02.cr3` | ZIGZAG_SIGN | 3 | 15972.7062 | 16104.3510 | -0.0439 |
| `sample-iso-high.cr3` | BIT0_SIGN | 0 | 14379.2004 | 14833.7870 | -0.0654 |
| `sample-iso-high.cr3` | BIT0_SIGN | 1 | 14840.4333 | 15215.4698 | 0.1055 |
| `sample-iso-high.cr3` | BIT0_SIGN | 2 | 14686.0559 | 15057.9549 | 0.1674 |
| `sample-iso-high.cr3` | BIT0_SIGN | 3 | 9280.9376 | 9944.9892 | -0.1762 |
| `sample-iso-high.cr3` | ZIGZAG_SIGN | 0 | 14802.5826 | 15237.9613 | 0.0074 |
| `sample-iso-high.cr3` | ZIGZAG_SIGN | 1 | 15251.7093 | 15619.5864 | 0.0615 |
| `sample-iso-high.cr3` | ZIGZAG_SIGN | 2 | 15235.3953 | 15601.7275 | 0.0599 |
| `sample-iso-high.cr3` | ZIGZAG_SIGN | 3 | 9510.7316 | 10173.6277 | -0.1544 |
| `sample-tele.cr3` | BIT0_SIGN | 0 | 3064.9098 | 3212.3865 | -0.3217 |
| `sample-tele.cr3` | BIT0_SIGN | 1 | 3724.3454 | 3895.4192 | -0.2374 |
| `sample-tele.cr3` | BIT0_SIGN | 2 | 3725.6190 | 3900.8231 | -0.2827 |
| `sample-tele.cr3` | BIT0_SIGN | 3 | 1420.0106 | 1614.7603 | -0.0032 |
| `sample-tele.cr3` | ZIGZAG_SIGN | 0 | 3593.8393 | 3666.3090 | 0.1682 |
| `sample-tele.cr3` | ZIGZAG_SIGN | 1 | 4142.8230 | 4267.4545 | 0.0256 |
| `sample-tele.cr3` | ZIGZAG_SIGN | 2 | 4144.7624 | 4276.1603 | -0.2399 |
| `sample-tele.cr3` | ZIGZAG_SIGN | 3 | 2194.4887 | 2277.6606 | 0.0601 |
| `sample-wb-tungsten.cr3` | BIT0_SIGN | 0 | 14389.1505 | 14829.2055 | 0.0837 |
| `sample-wb-tungsten.cr3` | BIT0_SIGN | 1 | 14901.0708 | 15270.9082 | 0.1420 |
| `sample-wb-tungsten.cr3` | BIT0_SIGN | 2 | 14862.0166 | 15233.3961 | 0.0853 |
| `sample-wb-tungsten.cr3` | BIT0_SIGN | 3 | 9287.8478 | 9973.1680 | -0.1996 |
| `sample-wb-tungsten.cr3` | ZIGZAG_SIGN | 0 | 14838.5488 | 15268.0246 | 0.0672 |
| `sample-wb-tungsten.cr3` | ZIGZAG_SIGN | 1 | 15276.8674 | 15642.9772 | 0.0013 |
| `sample-wb-tungsten.cr3` | ZIGZAG_SIGN | 2 | 15277.0915 | 15639.8898 | 0.0731 |
| `sample-wb-tungsten.cr3` | ZIGZAG_SIGN | 3 | 9154.5757 | 9749.7294 | 0.2029 |
| `sample-wide.cr3` | BIT0_SIGN | 0 | 3140.6703 | 3262.0319 | -0.2650 |
| `sample-wide.cr3` | BIT0_SIGN | 1 | 3591.2516 | 3761.4078 | -0.1062 |
| `sample-wide.cr3` | BIT0_SIGN | 2 | 3565.6443 | 3775.3095 | -0.2722 |
| `sample-wide.cr3` | BIT0_SIGN | 3 | 1651.9818 | 2031.1415 | -0.2020 |
| `sample-wide.cr3` | ZIGZAG_SIGN | 0 | 3593.9296 | 3666.6508 | 0.1680 |
| `sample-wide.cr3` | ZIGZAG_SIGN | 1 | 4049.8742 | 4190.2212 | 0.0402 |
| `sample-wide.cr3` | ZIGZAG_SIGN | 2 | 4111.1540 | 4255.0210 | -0.2357 |
| `sample-wide.cr3` | ZIGZAG_SIGN | 3 | 2228.7091 | 2283.6000 | 0.1944 |
| `sample.cr3` | BIT0_SIGN | 0 | 1694.5870 | 1754.5989 | -0.0276 |
| `sample.cr3` | BIT0_SIGN | 1 | 2082.4102 | 2134.9070 | -0.1542 |
| `sample.cr3` | BIT0_SIGN | 2 | 2103.8570 | 2145.7514 | 0.0294 |
| `sample.cr3` | BIT0_SIGN | 3 | 1454.8310 | 1631.2562 | -0.0202 |
| `sample.cr3` | ZIGZAG_SIGN | 0 | 2248.6985 | 2254.6138 | 0.0795 |
| `sample.cr3` | ZIGZAG_SIGN | 1 | 2454.3333 | 2515.2444 | -0.0183 |
| `sample.cr3` | ZIGZAG_SIGN | 2 | 2363.4904 | 2458.7431 | -0.1624 |
| `sample.cr3` | ZIGZAG_SIGN | 3 | 2197.4648 | 2524.9390 | 0.1031 |

## Status update

- D5 sign-mapping replacement: **REJECTED**.
- No production decoder change is justified by this experiment.
- Stage 6 remains the earliest failing/unverified pipeline stage, but the next experiment must vary a different residual/entropy semantic while keeping the already falsified zig-zag-only replacement closed.

## Next step

Select the next one-variable Stage-6 hypothesis from actual CRX entropy semantics (for example K adaptation or escape handling), define its acceptance threshold before execution, and preserve the same 11-fixture external-reference panel. Do not reopen generic predictor, alignment, CFA or isolated sign-mapping sweeps without new structural evidence.
