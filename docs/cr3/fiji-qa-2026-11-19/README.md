# Canon CR3 imports with the installed Fiji plugin, 19 November 2026

All ten headless imports passed on Java 21. The checks called `Cr3SensorImporter.open(Path)` and `Cr3ColorImporter.open(Path)` from the **installed 0.1.8-SNAPSHOT plugin**. The six CR3 libraries and the plugin JAR matched the seven entries in the install ZIP byte for byte. The candidate was built in documentation checkout `c09b70b`; its runtime source and package inputs match public commit `e370faa130ad33a611535fbff2fe4373ddd580a1`. [Source revision](https://github.com/carstenartur/Photographer/tree/e370faa130ad33a611535fbff2fe4373ddd580a1)

The same ten cases also passed with the **published 0.1.8 release** installed
from its GitHub ZIP. The released JARs, public update channel and preserved
updater history were verified after publication. See
[Published release verification](#published-release-verification) below.

| Input | Sensor dimensions | Sensor FNV-1a-64 | Color dimensions | Color FNV-1a-64 |
| --- | ---: | --- | ---: | --- |
| PowerShot G5 X Mark II RAW | 5632 × 3710 | `bbb2c3b2ecb4e45b` | 5496 × 3668 | `482d0cb8385a95c5` |
| EOS R RAW | 6888 × 4546 | `bfdafa4cdea7ee7c` | 6744 × 4500 | `ba1e15e786312378` |
| PowerShot G5 X Mark II C-RAW | 5632 × 3710 | `a90bf97e45a21c71` | 5496 × 3668 | `5c286fbe36253461` |
| EOS M50 C-RAW | 6288 × 4056 | `9bfade480f63376f` | 6024 × 4020 | `a4bfda0cf5099990` |

Each import ran in a fresh JVM. The eight reads above used Serial GC with `-Xmx768m` and produced zero compact-mode messages. The sensor output was one unsigned 16-bit plane. The color output had three unsigned 16-bit planes in composite mode, each with a 0–65535 display range. All images had one slice and one time point, and each importer returned its image without opening a window. Dimensions, channel count, bit depth and full-image fingerprints matched the earlier ImageIO results. [Reference fingerprints](../optimization-2026-11-19/channel-audit.json)

Two additional JVMs imported the G5 X Mark II RAW file using G1 and the plugin's default `AUTO` memory policy. The sensor import succeeded with `-Xmx51m`, and the color import with `-Xmx136m`. Each emitted one `CR3 low-memory:` line selecting a worker limit of one. Both outputs matched their respective fingerprints above. These heap limits describe single-image headless imports under the recorded conditions. Display rendering and other open images can require additional memory.

## Reproduce the headless checks

Use Python 3.11 or newer and a clean Fiji installation. The recorded Linux distribution was Fiji Latest dated 12 November 2026 with `ij-1.54p.jar` and `slf4j-api-2.0.17.jar`. Its ZIP SHA-256 was `fb3a5206ee94434907dea34b88b84f174de08ce25b3038c7a999bc554edcda15`. The probe was compiled using Eclipse Temurin 25.0.5.1+2 with `--release 21`; all ten headless imports ran on Debian OpenJDK `21.0.13+9-1-deb13u2-Debian`. [Fiji archive](https://downloads.imagej.net/fiji/archive/latest/20261112-1517/fiji-latest-linux64-jdk.zip)

The recorded `cr3-fiji-install.zip` SHA-256 was `6c9f7da16b7a18dc487091ac0db68d1f910ba6ba0ba2b39ff7787cc53d8edfca`. To reproduce the installation from source, use the linked instructions with the source revision above and extract the generated install ZIP into the clean Fiji application directory. The matrix records both source-tree identities. A rebuild or later change to version metadata or packaged guides may produce different ZIP or JAR digests. The runner verifies each installed JAR against the supplied ZIP and records the digests used for the replay. [Fiji archive build and installation](../fiji-update-site.md#build-both-archives)

Set these environment variables before running the command below.

| Variable | Value |
| --- | --- |
| `QA` | Directory containing this report and the accompanying probe |
| `REPO` | Photographer checkout containing the three repository CR3 fixtures and the pinned reference file |
| `FIJI`, `INSTALL_ZIP` | Fiji application directory and the CR3 install ZIP applied to it |
| `M50_FILE` | Downloaded EOS M50 C-RAW, entry 4656 from raw.pixls.us. The URL, CC0 license and SHA-256 are in the matrix. |
| `JDK25`, `JAVA21` | JDK 25 installation directory and path to the Java 21 executable |
| `OUT` | Fresh output directory for commands, exit codes, JVM output and digests |

```bash
python3 "$QA/run_checks.py" --repo "$REPO" --fiji "$FIJI" --install-zip "$INSTALL_ZIP" \
    --m50 "$M50_FILE" --javac "$JDK25/bin/javac" --java "$JAVA21" --output "$OUT"
```

The recorded compile and import commands appear in the matrix with local paths replaced by variables. The heap, collector and importer mode come from each case. Each measured fingerprint consumes both bytes of every unsigned 16-bit value, least significant byte first, in row, pixel and channel order. The color references are previously recorded ImageIO results outside the ImageJ adapter and use the original file orientation. They are not an external color-rendering reference. The runner preserves stdout and stderr for each invocation and checks the output separately from the compact-mode diagnostics. It accepts a compact worker limit from one to four because heap conditions can differ; the recorded runs selected one. All ten cases also passed when replayed with this runner. [Recorded matrix and artifact digests](matrix.json) · [ImageJ probe](Cr3ImageJProbe.java) · [Replay runner](run_checks.py)

## Fiji menu, display and package checks

The installed archive was also exercised in the full Fiji application using
Eclipse Temurin **21.0.13.1+2** and a 1 GiB heap. The existing
[menu verification macro](../../../cr3-imagej/src/it/verify-fiji-import.ijm)
opened the sensor and color commands. The sensor comparison against the
reference TIFF covered all **20,894,720 samples**, with maximum absolute
difference **zero**. The color command returned **5496 × 3668**, three channels,
one slice, one time point and 16-bit samples. In a 16 × 16 patch it found
**28 / 60 / 28** values between the levels available to expanded 8-bit RGB.
[Recorded macro result and runtime provenance](fiji-application.json)

For the same archive, an interactive import of `sample-tele.cr3` through
**File › Import › CR3 Color Image...** opened a 5496 × 3668, 16-bit composite.
The Channels Tool showed three enabled channels in Composite mode. Disabling
and then re-enabling the green channel changed the rendered colors and restored
the complete image. The data occupied approximately 115 MiB, excluding Fiji's
other allocations. The installed application exposed both CR3 menu commands.

To replay the installed macro test, set `SAMPLE_DIR` to a fresh directory containing
`sample.cr3` and a `reference.tiff` link to `sample.reference.tiff`. On Linux,
provide a graphical display or prefix the command with `xvfb-run -a`:

```bash
"$FIJI/fiji" --java-home "$JAVA21_HOME" --mem 1g --no-splash --allow-multiple -- \
    -batch "$REPO/cr3-imagej/src/it/verify-fiji-import.ijm" "$SAMPLE_DIR"
grep -Fx '5632x3710 16 bits 20894720 samples max=0' "$SAMPLE_DIR/result.txt"
```

The Fiji Maven verification reported **715 tests: 714 passed, one optional
vector test skipped, zero failures or errors**. The optional Java 25 backend
was separately exercised in the [complete-import study](../optimization-2026-11-19/README.md).
The updater installed and verified seven JARs. Both candidate ZIPs contained
identical current JAR bytes, and the update-site ZIP preserved all 28 historical
JAR payloads and the preceding updater records. The seven previously current
0.1.7 JAR entries became historical versions with unchanged checksums.

## Published release verification

After [0.1.8 was published](https://github.com/carstenartur/Photographer/releases/tag/v0.1.8),
both Fiji ZIPs were downloaded anonymously and checked against their GitHub
asset SHA-256 digests. The stable commit is
[`20ca2d0`](https://github.com/carstenartur/Photographer/commit/20ca2d01ec07964abd076d80ae6d38fcc711c780);
its parent is the verified release source `d027bdb`. Only the reactor POMs
changed when Maven prepared the stable version.

| Released archive | SHA-256 |
| --- | --- |
| `cr3-fiji-install.zip` | `6dd55d4fb19c5da50ab782c921e55746f74bdacf68b625f2ad8a11a143e5440f` |
| `cr3-fiji-update-site.zip` | `3e849dc71059e7a3a05547f4c21dba17caaa24a9984dabf3892391a4fe1f7654` |

The anonymous update-site downloads matched the archive byte for byte: the
database, `version.txt` identifying `0.1.8`, all seven current JARs and the
Groovy color example. All 28 JAR payloads from the preceding 0.1.7 site were
preserved with their updater checksums and bytes unchanged. The new database
contains 35 current and historical payloads in total.

All **185 class files** across the released JARs are byte-identical to the
candidate used for the menu and display checks above. Within the seven runtime
JARs, only packaged guides and release metadata differ. The released install ZIP
was applied to a separate Fiji installation, and `run_checks.py` passed all ten
cases in fresh Java 21 JVMs. Dimensions and full-image fingerprints matched the
table, and channel counts and sample depths matched the results described above.
The two constrained-heap imports again
emitted exactly one compact-mode message each, selecting one worker.

The graphical checks used the candidate; the stable release was verified by
these independent headless replays and the class-file comparison. The
[release verification record](release-validation.json) contains the downloaded
file digests, archive comparisons, runtime details and individual replay results.

### Standalone Maven Central example

The [standalone example](../imageio.md#run-the-minimal-example) was also built
with its default `cr3.version=0.1.8` and an initially empty Maven cache. Maven
3.9.11 on JDK 25 compiled the example for Java 21 and resolved all six released
CR3 libraries from Maven Central. The six JARs and six POMs matched independent
anonymous HTTPS downloads byte for byte. Running the example with `sample.cr3`
on Java 21 returned exit code zero and the documented output:

```text
5632 x 3710 Bayer mosaic, 1584 distinct sensor values, maximum 3512
```

The `central_example` section of [the verification record](release-validation.json)
contains the source and input digests, public artifact URLs and checksums, Maven
repository identifiers, runtime and reproducible commands.
