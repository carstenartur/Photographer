# Reproducing the memory study

Use the build JDK 25, Maven and the same input hashes recorded in
[`provenance.json`](provenance.json). Production CR3 classes target Java 21.
The recorded environment is Linux; adjust `taskset` CPU IDs to the CPUs available
on your host. Results on a different CPU, collector, JVM or heap are a new run,
not an expectation of identical timing.

## Source versions and helpers

The baseline is `3f7312c679a7deca1ec831103c8eff5fbb814634`. The final runtime
source is the tree of PR 427 head
`26a020291c759e64ee1effef38c544d828219856`, also preserved by squash commit
`14fafdc3fbc3dd25793b94c63096cd6abb8d770a`. Both include the corrected inclusive
active-area crop. Build each source revision in a separate worktree:

```bash
git worktree add --detach target/cr3-memory-baseline 3f7312c679a7deca1ec831103c8eff5fbb814634
git worktree add --detach target/cr3-memory-final 14fafdc3fbc3dd25793b94c63096cd6abb8d770a
mvn -f target/cr3-memory-baseline/pom.xml -DskipTests -Pbenchmarks -pl canon-cr3-benchmark -am package
mvn -f target/cr3-memory-final/pom.xml -DskipTests -Pbenchmarks -pl canon-cr3-benchmark -am package
```

Each produces `canon-cr3-benchmark/target/benchmarks.jar` beneath its worktree.
Keep those binaries immutable during measurements. The archived measurement
JAR hashes identify the actual binaries used; the source/tree and helper pins
also identify their content independently of packaging details.

The original timed final JAR additionally contained the extended probe before
its later malformed-input null guard. Its production sources match the final
runtime tree above. Keep the frozen helper source/class selection explicit:

| Panel | Frozen source | Compilation target used in the study |
| --- | --- | --- |
| Ordinary ImageIO controls, scheduler/detail screens | [normal/Cr3ImportProbe.java](sources/normal/Cr3ImportProbe.java) | Java 25 |
| Matching RGB16 destination | [destination/Cr3DestinationProbe.java](sources/destination/Cr3DestinationProbe.java) | Java 21, executed on Java 25 |
| FAST/LOW timing with orientation override | [memory/Cr3ImportProbe.java](sources/memory/Cr3ImportProbe.java) | Java 25 |
| Complete correctness fingerprints and small-heap imports | Same memory source | Java 21, executed on Java 21 |

Compile each helper into its own directory, against the relevant benchmark JAR,
and place that directory **before** the JAR on the runtime classpath. For example:

```bash
javac --release 21 \
  -cp target/cr3-memory-final/canon-cr3-benchmark/target/benchmarks.jar \
  -d target/cr3-memory-probe21 \
  docs/cr3/memory-2026-11-19/sources/memory/Cr3ImportProbe.java
javac --release 25 \
  -cp target/cr3-memory-final/canon-cr3-benchmark/target/benchmarks.jar \
  -d target/cr3-memory-probe25 \
  docs/cr3/memory-2026-11-19/sources/memory/Cr3ImportProbe.java
```

Use JDK 25's default target for a Java 25 helper. Class hashes of the actual
recorded helpers are in the manifests. The current source helper's later
null guard changes only invalid metadata initialization and has its own
24-test verification; it was not silently substituted into the frozen runs.

## Inputs and timed runs

The three in-repository files are under
`canon-cr3-imageio/src/test/resources/samples`. The fourth is the registered
CC0 EOS M50 C-RAW input 4656; its URL and SHA-256 are recorded in the provenance.
See the earlier [comparison setup](../performance-2026-11-19/comparison-setup.md)
for input acquisition and verification. Do not substitute similarly named files.

[`sources/public_measurements.py`](sources/public_measurements.py) is the
unchanged serial runner used for the ordinary ImageIO, destination-copy and
rejected-experiment panels. Supply an input-manifest JSON list and an extra-input
JSON entry, adapting only their filesystem paths. Use `--stage final`, explicit
baseline/final JARs and the corresponding frozen helper directory. The original
manifests and plans preserve every actual command and hash. The `final` variant
label means the candidate in each individual panel, including rejected ones.

The ordinary ImageIO panel uses `--modes default`; the destination panel uses
`--modes rgb16 --probe-main com.photographer.focusstack.cr3.benchmark.Cr3DestinationProbe`.
Both use `--cpus 4 --forks 3 --warmup 3 --iterations 5`. Set
`--optimized16-jar` to the baseline JAR as in the archived commands; that is an
unused reference entry for these panels, not a third measured variant.

Two separately recorded follow-ups investigate specific signals in the original
default controls. The G5 RAW timing confirmation uses `--input-index 0 --modes
default --cpus 4 --forks 5 --warmup 8 --iterations 12`. The G5 C-RAW RSS
confirmation uses `--input-index 2 --modes default --cpus 4 --forks 5 --warmup 3
--iterations 5`, preserving the original warmup/measurement settings. Keep these
panels separate; do not replace or pool the original observations.

[`sources/measure_memory_modes.py`](sources/measure_memory_modes.py) imports the
unchanged runner beside it. It takes one combined four-input JSON list and compares both
policies in the **same** supplied runtime JAR:

```bash
python3 docs/cr3/memory-2026-11-19/sources/measure_memory_modes.py \
  --input-manifest memory-inputs.json --input-index 0 1 2 3 \
  --cpus 4 --modes rgb8 rgb16 --orientation 6 \
  --jar target/cr3-memory-final/canon-cr3-benchmark/target/benchmarks.jar \
  --java /path/to/jdk25/bin/java --probe-classes target/cr3-memory-probe25 \
  --forks 3 --warmup 3 --iterations 5 --output new-memory-measurements
```

All output directories must be new. Run panels sequentially after builds finish.
Do not run other benchmarks, tests or decode work concurrently. Retain failed
processes, every observation, stdout and stderr. LOW_MEMORY should emit eight
CR3 warnings per process: one per warmup or measured read. The runner verifies
those counts and the requested options in each JSON observation.

## Correctness and constrained heap

Use Java 21, the Java 21 memory helper, `--warmup 0 --iterations 1 --checksum`,
the chosen collector and a fresh process for every case. Match dimensions,
channels, component depth and full numeric FNV fingerprints before interpreting
the timing panel. The 512 MiB reference matrix checks all four files, orientations
1/6, RGB8, both RGB16 transfers and native sensor output.

The correctness archive includes the three scripts exactly as executed, alongside
all 76 retained process results. Their paths refer to the original workspace and
must be adapted. The first two scripts stopped at insufficient AUTO heaps
(96 MiB and 208 MiB Serial respectively); the separate continuation completed
the remaining demonstrated caps. Those failed attempts are intentional retained
evidence, not instructions to expect every recorded script to exit successfully.

For compact allocation and exact value regressions independent of the large
external inputs, run the normal tests containing `Cr3LowMemoryImportTest`,
`Cr3MemoryPolicyTest`, `Cr3CompactDevelopmentTest`,
`ExifOrientationCompactTest`, `ExifOrientationTransformerTest`,
`CanonCr3MemoryModeTest` and
`CanonCr3DestinationMemoryTest`. The committed tests construct first-party
fixtures or use existing test resources.

The two rejected experiment patches are available under [`experiments`](experiments).
Apply each separately to the baseline when reproducing its screen. Neither
patch belongs on the final runtime. The scheduler pilot uses one fork; the
bounded-detail screen uses three and measures native-sensor imports only.
