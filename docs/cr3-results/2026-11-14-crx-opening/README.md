# RG3 · Locating the first native values of a lossless CRX subband

The two-site Java research reader recovered both native Bayer values on all 88 discovery streams and all 32 held-out streams. It uses only the compressed bytes and bit depth when computing a value. The original sensor rasters are read separately for comparison. The first value also passed 30 exact, prospective predictions on deliberately changed files. [Preregistration and input freeze](https://github.com/carstenartur/Photographer/issues/334)

## Observed layout

Offsets count from Photographer’s extracted subband start, MSB first, with the first bit at offset 0. The inputs have a 14-bit, four-plane, zero-wavelet-level layout. The first two sites are consecutive in the same tile and plane. In the uncropped sensor mosaic their coordinates are (x, y) and (x + 2, y).

1. Require the first 18 bits to have unsigned value 128. Read bits **18–31** as unsigned integer U. For even U, the first value is 8192 + U/2. For odd U, it is 8192 − (U + 1)/2. For example, the unchanged tile-0/plane-0 EOS R word 12287 yields 2048.
2. Starting at bit **32**, count q zero bits up to a terminating one. For q < 41, read the next two bits as r and use V = 4q + r. For q = 41, the observed branch reads the next 21 bits as V. Add V/2 to the first sample for even V, or subtract (V + 1)/2 for odd V. Record the next unread bit. The reader reports an unsupported result for larger q, an unknown prefix or an out-of-range second value.

This is an operational reading for the measured layout. In the original streams the 32 opening bits could equally be described as ten zeros, a one, and a 21-bit unsigned quantity whose high seven bits were zero. The observations do not identify a unique field start or delimiter. At the other end, bit 32 is where the successful two-site reader starts its second code. The external decoder rejected the downstream edits below, so they provide no independent pixel-level proof of that boundary.

### Byte origin in the container

In all eleven discovery files and all four held-out files, CMP1 declares a 216-byte header. Adding the two declared tile sizes gives the exact track length, as does summing their subband sizes plus 216. Photographer’s current header parser also consumes four following zero bytes and uses 220 as the origin. Consequently, the last declared extent in that coordinate system ends four bytes past the track. This reader bounds its actual reads to the opening bytes inside the track.

The four bytes immediately before each extracted start were zero at all 88 discovery and all 32 held-out starts. Counting from the CMP1-derived start assigns **bits 50–63** to the very same 14 physical bits that the extractor calls **18–31**. The first one moves from bit 10 to bit 42, and the second-site code begins at bit 64. The controlled mutations establish which bytes affect the first sensor value. The alternative origin changes how the preceding zero run can be interpreted, which matters when deriving subsequent entropy rules.

## Original sensor values

| Evidence split | Files | Tile/plane streams | First exact | Second exact | Second ordinary / literal |
|---|---:|---:|---:|---:|---:|
| Discovery, EOS R and PowerShot G5 X Mark II, manifest-bound LibRaw TIFFs | 11 | 88 | 88 | 88 | 83 / 5 |
| Held out, new EOS R and PowerShot G5 X Mark II captures, DNGLab PGMs | 2 | 16 | 16 | 16 | 16 / 0 |
| Held out, previously unseen EOS M50 and EOS RP models, DNGLab PGMs | 2 | 16 | 16 | 16 | 16 / 0 |

We derived the candidate from the first row and froze the arithmetic and the four additional RAW identities in issue #334 before reading held-out entropy or reference values. Each held-out file contributed two tiles and four planes, eight comparisons per site. All 32 held-out streams were supported. The five 21-bit literal cases came from EOS R discovery files. The held-out first values ranged from 507 to 2052, extending below the discovery range of 1984–2149. The holdout tested ordinary quotients up to 31. [Discovery measurements](discovery.tsv) · [Held-out measurements](holdout.tsv) · [CC0 source files and SHA-256 digests](../../../scripts/research/public-crx/lossless-holdout.tsv)

## Controlled bit changes

Each experiment started with a fresh copy of the relevant payload bytes in the original EOS R low-ISO or PowerShot G5 X Mark II wide file, in tile 0, plane 0. DNGLab 0.8.0 decoded the altered CR3 through its normal executable interface. For every accepted copy, the harness compared the entire uncropped mosaic and retained the changed site counts and output hash.

| Intervention | Accepted / tried | Observed native results |
|---|---:|---|
| Flip each of offsets 18–31 separately on each camera | 28 / 28 | Every first value equaled its predeclared prediction. Changing bit 31 exercised the even branch absent from the discovery set. EOS R changed from 2048 to 14335 and G5 X II from 2052 to 14331. |
| Exchange only those 14 bits between cameras, in both directions | 2 / 2 | The first values swapped exactly, from 2048 to 2052 and from 2052 to 2048. |
| Flip preceding bit 10, 11 or 17 separately | 4 / 6 | Both bit-10 copies were rejected. Changes to bit 11 or 17 produced zero at both opening sites on both cameras. |
| Flip bit 32 or 33 separately | 0 / 4 | DNGLab reported a corrupt image for every copy from both cameras. |
| RG3b, separately declared after those 40 cases, flip each of the two ordinary remainder bits | 0 / 4 | EOS R offsets 35/36 and G5 X II offsets 33/34 were all rejected. G5 X II offset 33 repeats the earlier identical variant. |

Across the 30 single-bit and swap predictions, differences were confined to the selected tile and plane. In these files the changed first value propagated to every site of that tile/plane. The rejection controls yielded no sensor values. The accepted preceding-bit controls show that the 14-bit rule is conditional on the measured prefix. [Original 40 trials](interventions.tsv) · [RG3b controls and forecasts](second-remainder.tsv)

## Reproduction

Run from the repository root with Java 25 and Maven. The focused verifier regenerates the discovery table from the 11 original fixtures. Its opening experiment mode also prepares the four CC0 inputs and the pinned Linux x86-64 DNGLab release, runs all stages and compares the four reports byte for byte. Use an absolute scratch directory.

```sh
bash scripts/verify-cr3-research.sh --focused
scratch=$(mktemp -d)
bash scripts/verify-cr3-research.sh --opening "$scratch"
```

The baseline was `c6cac45689bad6d7be0f0a057887a9ae9b7c8255`. Inputs for the Java reader were measurements from Photographer's own extractor and native sensor values. LibRaw and DNGLab supplied external reference outputs. No third-party decoder implementation or ArtCraft codec description was used to derive the reader. The research result applies to the opening of the measured lossless streams. The next experiment can start at the recorded end bit and predict the third site before consulting its reference value. [Java reader](../../../canon-cr3-research/src/test/java/com/photographer/focusstack/cr3/research/LosslessOpeningSamples.java)
