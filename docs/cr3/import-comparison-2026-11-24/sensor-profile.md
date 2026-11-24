# Where Java spends time decoding the Bayer mosaic

These Java Flight Recorder profiles cover the native sensor import at revision `6cb25a7`. The [camera inputs and tool versions](manifest.json) are the same as in the [full-import comparison](README.md). No demosaicing or color conversion runs in this mode.

| Input | All Java execution samples | Observed method | Samples | Share |
| --- | ---: | --- | ---: | ---: |
| PowerShot G5 X Mark II C-RAW | 2,821 | `CrxWaveletDetail.read` anywhere on the stack | 1,801 | 64% |
| EOS R8 C-RAW | 5,177 | `CrxWaveletDetail.read` anywhere on the stack | 3,053 | 59% |
| EOS R8 C-RAW | 5,177 | `CrxEntropyState.readRunLength` as top frame | 1,963 | 38% |
| EOS R RAW | 5,096 | `CrxNativeLosslessPlane.readRow` as top frame | 2,754 | 54% |
| EOS R RAW | 5,096 | `BitReader.readBit` as top frame | 1,825 | 36% |

The denominator counts all `jdk.ExecutionSample` events with a nonempty stack in one process. A method anywhere on the stack includes work in its callees. A top-frame count credits a single method per event. The R8 run-length samples can therefore contribute to its detail-row total. The percentages describe sampled execution on this host. A wall-time improvement needs an independent timing run.

## Candidate changes

Both C-RAW files spend most samples in the detail-row decoder. Compare the run-length distribution and the number of flag bits consumed on the R8, R5 Mark II and G5 before changing the bit reader. A previous run-length lookup shortened R8 Bayer imports by 8–19% in two screening pairs, with mixed results on the other recordings. Knowing which run lengths account for the work would help select a common fast path. [Previous run-length experiments](../full-import-performance-2026-11-22/README.md#other-experiments)

For EOS R lossless RAW, inspect the branches for flat neighborhoods and the predictor and Rice updates in the ordinary row loop. An earlier combined prediction-flag and residual read increased EOS R sensor import time. [Previous entropy-decoder experiments](../optimization-2026-11-19/README.md#entropy-decoder-experiments)

Compare any candidate against the same revision across several warm JVMs without JFR. Check complete Bayer rasters byte for byte, then measure the normal RGB16 `ImageIO.read(File)` on the same originals.

## Reproduce the profiles

On an AMD EPYC 9V74 Linux host, each file ran in its own Java 21.0.13 process on logical CPUs 4–7 with Serial GC. The probe completed twelve sensor reads before recording another 32. The recording covers the whole second probe invocation, including all worker threads and between-read activity. The filesystem cache was warm.

Build the [Java import probe](../../../scripts/benchmarks/README.md#java-probe) for Java 21. Set `cp` to the Java 21 probe classes followed by the benchmark JAR. Run the following from the repository root with JDK 21's `java` and `jfr` on the path. Use the EOS R or R8 file from the manifest in place of the G5 file to repeat the other profiles.

```bash
profile_dir=$(mktemp -d)
cat > "$profile_dir/ProfileSensor.java" <<'JAVA'
import java.nio.file.Path;
import java.time.Duration;
import jdk.jfr.Recording;
import com.photographer.focusstack.cr3.benchmark.Cr3ImportProbe;

class ProfileSensor {
    public static void main(String[] args) throws Exception {
        try (Recording recording = new Recording()) {
            recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(2)).withStackTrace();
            Cr3ImportProbe.main(new String[]{"--input", args[0], "--mode", "sensor",
                    "--warmup", "11", "--iterations", "1", "--gc-between"});
            recording.start();
            Cr3ImportProbe.main(new String[]{"--input", args[0], "--mode", "sensor",
                    "--warmup", "0", "--iterations", "32", "--gc-between"});
            recording.stop();
            recording.dump(Path.of(args[1]));
        }
    }
}
JAVA
javac --release 21 -cp "$cp" "$profile_dir/ProfileSensor.java"
input=canon-cr3-imageio/src/test/resources/samples/research/g5xm2-detail-iso1600-ss0p4s-ff1p6-iq3-20260511-201012-02.cr3
taskset -c 4-7 java -Xms24m -Xmx768m -XX:+UseSerialGC -XX:ActiveProcessorCount=4 \
    -cp "$profile_dir:$cp" ProfileSensor "$input" "$profile_dir/sensor.jfr"
jfr print --events jdk.ExecutionSample --stack-depth 64 "$profile_dir/sensor.jfr"
```
