# Canon CRX mode-bit experiment and C-RAW sensor references

On 14 November 2026 we tested a single change to Photographer's lossless research decoder. It moved the bit reader forward by one bit and left the first incorrect Bayer value unchanged in **all 88 streams**. Two public C-RAW files now have separately reproduced full-sensor references for the EOS R and PowerShot G5 X Mark II. [Preregistered experiment and control](https://github.com/carstenartur/Photographer/issues/332)

## Lossless mode decision

Canon publication JP2017192077A describes a decoder that checks causal neighbours before considering a run. We tested whether its low-frequency rule applies at the beginning of the zero-decomposition streams in our lossless panel. We used the example boundary value zero and the published flag polarity. This mapping and boundary are assumptions. Figures 10 and 14 describe an embodiment and do not identify the grammar of these camera files. [Canon publication](https://patents.google.com/patent/JP2017192077A/en)

The two variants share RG1's first-reference-sample diagnostic, predictor, Rice reader, sign convention and K update. In the candidate, equality of west, north, northwest and northeast makes the next bit eligible. Zero selects normal predictive decoding. One requires a first run value equal to the west neighbour. A compatible positive run would stop the comparison before an unspecified run length could influence the score. The oracle supplies the initial prediction only. Unavailable neighbours retain zero. The measured prefix covers up to 64 sites of the first plane row. Scores require exact native values. [RG1 baseline](../2026-07-23-RG1-libraw-guided-first-divergence.md)

| Camera | Streams | Improved | Same first error | Worse | Unresolved |
|---|---:|---:|---:|---:|---:|
| EOS R | 24 | 0 | 24 | 0 | 0 |
| PowerShot G5 X Mark II | 64 | 0 | 64 | 0 | 0 |

In each variant, 71 streams matched one site, nine matched two, four matched three, two matched four and two matched six. At the origin all four missing neighbours were zero, and the candidate consumed flag zero at bit offset 0 in all 88 streams. At the baseline mismatch the west neighbour already held the nonzero seed and the other first-row neighbours were zero. None of the 88 streams qualified for another flag there.

Each extracted payload began with **exactly ten zero bits followed by a one**. The candidate was still reading those initial zeros at the first reference error. For example, in the high-ISO EOS R file, tile 0 plane 0, the second plane sample at sensor coordinate (2, 0) should be **2006**. Both variants produced **2003**. The baseline consumed bit interval [2, 3) for that residual and the candidate consumed [3, 4). Treating the first bit as this particular decision cannot explain our failure. The first one at offset 10 is an observed boundary whose meaning is open. [Per-stream measurements](lossless-prefix.tsv) · [Family totals](lossless-summary.tsv)

## One-bit control on original camera files

We used Photographer's extractor to locate the first byte of tile 0, plane 0, subband 0 in each selected original. A private copy differed only in that byte's most significant bit. DNGLab 0.8.0 produced a full sensor PGM for each original.

| Lossless file | Absolute byte offset | Original → copy | DNGLab on the copy |
|---|---:|---|---|
| EOS R, eosr-iso-low.cr3 | 4,314,247 | 00 → 80 | Raw decode I/O error, exit 2 |
| G5 X Mark II, sample-wide.cr3 | 3,423,873 | 00 → 80 | Raw decode I/O error, exit 2 |

Changing that bit affected acceptance in both files. Neither modified file produced sensor values, so the test cannot localize a pixel or identify the bit's meaning. The [control measurements](first-bit-control.tsv) bind the input and mutated files, offsets and original decoded output to hashes.

## Public C-RAW reference values

We ran the Linux x86-64 releases of LightCraft 0.21.1 and DNGLab 0.8.0. LightCraft exported an uncompressed, single-component CFA DNG. DNGLab exported a 16-bit sensor PGM. A Java comparison checked the headers, normalized byte order and compared every sensor site in raster order before cropping, black subtraction, demosaicing or colour processing.

| Camera and raw.pixls.us ID | Dimensions | Compared sites | Differences | Photographer RAW_LINEAR |
|---|---:|---:|---:|---|
| EOS R, 4543 | 6888 × 4546 | 31,312,848 | 0 | C-RAW unsupported after header validation |
| PowerShot G5 X Mark II, 3506 | 5632 × 3710 | 20,894,720 | 0 | C-RAW unsupported after header validation |

Both references also reproduce the published ArtCraft sensor checksums. A one-bit change to one G5 X Mark II reference sample was detected at (0, 0). Photographer read 14-bit, four-plane, three-level headers on both files. Its explicit RAW_LINEAR entry returned an unsupported-codec exception before returning any samples. This evidence establishes reference mosaics for these two CC0 files. [Comparison and sensor digests](external-sensors.tsv) · [Photographer diagnostics](photographer-craw.tsv) · [ArtCraft reference measurements](https://github.com/storytold/lightcraft/blob/c4b899f9d573a4910991620e8eb01eac6a388529/docs/cr3.md)

## Reproduction

Baseline revision `60bf06282b17d03ba170ee629de6ff88e56ff524`. Run from the repository root with Java 25 and Maven. The lossless run reads the 11 original fixtures and their manifest-bound RAW_LINEAR TIFFs already in the repository.

```sh
mvn -B --no-transfer-progress verify -Pcr3-research-it -pl canon-cr3-research -am \
  -Dit.test=PatentRunModeIT -Dcr3.patent.run-mode.enabled=true -Djava.awt.headless=true
python3 scripts/manage_cr3_generated_evidence.py --root . compare RG2-metrics \
  --source-root canon-cr3-research/target/patent-run-mode
```

Use an absolute scratch directory for the two CC0 samples and pinned Linux executables. The script verifies the downloads, executes both binaries and prints the comparison table. The second command exercises Photographer's C-RAW entry and the one-bit lossless control. The external programs stay outside the product build.

```sh
scratch=$(mktemp -d)
bash scripts/research/public-crx/verify-public-crx.sh "$scratch" > "$scratch/external-sensors.tsv"
cmp "$scratch/external-sensors.tsv" docs/cr3-results/2026-11-14-crx/external-sensors.tsv
mvn -B --no-transfer-progress verify -Pcr3-research-it -pl canon-cr3-research -am \
  -Dit.test=PublicCrawPhotographerIT,LosslessFirstBitControlIT -Djava.awt.headless=true \
  -Dcr3.public.craw.enabled=true -Dcr3.public.craw.dir="$scratch" \
  -Dcr3.first-bit.control.enabled=true -Dcr3.first-bit.dnglab="$scratch/dnglab"
```

The patent and Photographer's existing research logic supplied the candidate semantics. For the external comparisons we used release binaries, their observable output and the published input and sensor hashes. The tests retain no private photographs in their reports. The next question is whether the initial ten zero bits and the first one encode a field or the first entropy symbol. Answering it requires an independent observation that distinguishes those interpretations. A blind eleven-bit skip has no evidentiary basis.
