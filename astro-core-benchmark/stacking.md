# Measuring astronomical stack combination

Run from the checkout root with JDK 25. The optional benchmark profile builds the JMH runner.

```bash
mvn -B -Pbenchmarks -pl astro-core-benchmark -am package -DskipTests
java -jar astro-core-benchmark/target/astro-benchmarks.jar AstroCombinationBenchmark.stack \
  -prof gc -rf json -rff astro-results.json
```

Each case builds the same spatial background and sparse bright transients outside the timed section, then combines four or sixteen registered frames with identity transforms. RGB8 uses packed 8-bit RGB and GRAY16 uses single-channel unsigned-short samples. `gc.alloc.rate.norm` measures allocated bytes per operation and includes the output image. Input frames already exist at measurement time. Allocated bytes do not measure peak resident memory.

## Reference run on 2026-11-15

AMD EPYC 9V74, OpenJDK 25.0.5+8, JMH 1.37, one worker thread per fork. Each case used two fresh JVMs (`-Xms128m -Xmx512m`), one 250 ms warm-up and two 300 ms timed iterations per JVM. The table gives mean operation time in milliseconds and allocated MiB per operation in parentheses.

| Size | Format | Frames | Mean | Median | Sigma-clipped mean |
|---|---|---:|---:|---:|---:|
| 640x480 | RGB8 | 4 | 7.9 (9.38) | 14.4 (1.17) | 32.2 (1.17) |
| 640x480 | RGB8 | 16 | 22.3 (9.38) | 60.3 (1.18) | 80.3 (1.18) |
| 640x480 | GRAY16 | 4 | 19.8 (0.59) | 18.4 (0.59) | 31.6 (0.59) |
| 640x480 | GRAY16 | 16 | 68.7 (0.59) | 68.9 (0.59) | 108.3 (0.59) |
| 1920x1080 | RGB8 | 4 | 92.9 (63.29) | 106.5 (7.91) | 173.1 (7.92) |
| 1920x1080 | RGB8 | 16 | 264.3 (63.29) | 426.7 (7.92) | 601.2 (7.92) |
| 1920x1080 | GRAY16 | 4 | 181.0 (3.96) | 149.1 (3.96) | 209.1 (3.96) |
| 1920x1080 | GRAY16 | 16 | 445.9 (3.96) | 508.3 (3.96) | 798.9 (3.96) |

For packed RGB8, the historical Mean implementation allocates three full-image 64-bit accumulators plus counts. Median and sigma clipping reuse per-frame scratch storage and allocate the output raster. Native unsigned-short processing reuses per-frame scratch storage for all three modes.

Run the normal JMH defaults for longer measurements. To reproduce the shorter reference run, add `-f 2 -wi 1 -i 2 -w 250ms -r 300ms -jvmArgs '-Xms128m -Xmx512m'`. Compare only like-for-like parameter tuples on the same machine. This initial run supplies a reporting baseline; timing gates require repeated unchanged-revision measurements on the intended CI worker. Exact sample and outlier behavior is enforced by [the stacking tests](../astro-core/src/test/java/com/photographer/focusstack/astro/AstroStackingServiceTest.java).
