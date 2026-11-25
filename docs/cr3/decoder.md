# CR3 Decoder and Processing

The native Java RAW import reconstructs the measured 14-bit, four-plane CRX layouts.
The [camera coverage table](camera-coverage.md) lists the tested RAW and C-RAW modes
with their sensor references.

## Stable container and metadata layers

### `cr3-container`

Supported responsibilities:

- high-level CR3 detection and parsing;
- immutable container and track models;
- CMP1/IAD1 facts projected into the high-level model;
- embedded preview and thumbnail payload extraction.

External entry points include `Cr3Parser`, `Cr3Container`, `Cr3Track` and `Cr3PreviewExtractor`. Low-level BMFF box classes are internal.

`Cr3Parser` projects the first sample description, first chunk offset (`co64` or `stco`) and fixed or first `stsz` sample size into each track descriptor. For the supported single-sample layout, `Cr3Track.dataOffset()` is an absolute byte position in the input and `dataSize()` is the length of the sample. When the CR3 starts inside a stream, the parser adds the stream position of that start to the stored chunk offset. The native decoder uses the resulting range for the selected RAW track. [Tracks and sample ranges](format.md#tracks-and-sample-ranges)

### `cr3-metadata`

Supported responsibilities:

- `Cr3Metadata` consumer model;
- EXIF/TIFF, Canon MakerNote and CTMD-derived information;
- sensor dimensions, bit depth and active-area projection.

Parser helpers and offset/layout interpretation are internal. Consumers should depend on `Cr3Metadata`, the facade or ImageIO metadata rather than low-level parsing classes.

The parser reads the contiguous twelve-byte directory descriptors together, up to the existing limit of 1,000 entries, before seeking to separately stored values. A TIFF directory entry holds one or two unsigned `SHORT` values inline. Longer arrays, including Canon ColorBalance, point to values elsewhere in the TIFF block. After checking the count (up to 8,192 words) and the array extent, the parser seeks once and calls `ImageInputStream.readFully(short[], offset, length)` on chunks of up to 1,024 words (2 KiB). The stream's TIFF byte order controls conversion of each word. `Short.toUnsignedInt` then widens the words into the metadata array. Tests exercise both byte orders, values above 32,767, chunk boundaries and physically truncated inputs. [TIFF parser](../../canon-cr3-metadata/src/main/java/com/photographer/focusstack/cr3/metadata/TiffIfdParser.java) · [Numeric metadata tests](../../canon-cr3-metadata/src/test/java/com/photographer/focusstack/cr3/metadata/TiffNumericMetadataTest.java) · [File-read measurements](rgb16-import-2026-11-24.md#tiff-metadata-file-reads)

## Native codec layer

`cr3-codec` owns:

- CRX header parsing;
- bit readers and Rice-Golomb primitives;
- entropy/predictor state;
- tile, plane and subband reconstruction;
- inverse quantization and integer 5/3 wavelet reconstruction for the measured three-level C-RAW layout;
- the `Cr3RawImage` linear Bayer transfer model.

### From sample range to Bayer rows

`Cr3Decoder` selects the first `CRX_BIG` or `CRX_RAW` track and uses `CMP1` for the native image dimensions and bit depth. `CMP1` expresses image and tile dimensions in sensor sites. With four positional planes, each tile plane is half as wide and half as tall as its tile. The compressed sample starts with a directory, whose length in bytes is `CMP1.mdatHeaderSize()`. `CrxHeader` takes that length as the origin of the entropy data and accumulates the declared subband lengths in directory order. The native codec checks the profile, geometry and directory extents before allocating output. [Codec parameters in CMP1](format.md#codec-parameters-in-cmp1) · [CRX directory and entropy origin](format.md#directory-records-and-entropy-origin)

`CrxTrackInput` translates sample-relative byte positions to the backing input and bounds each directory or entropy read to its own slice. Byte-array input shares the array directly. A path opened by `Cr3Decoder.setInput(Path)` uses positional reads from the same file handle used for container parsing, and the decoder closes that handle on reset or close. A supplied `ImageInputStream` stays caller-owned. Position it at the start of the CR3 before setting the input. The codec synchronizes seek and read operations during refills, which may move the stream position. Keep the supplied stream open and dedicated to the decode until it returns.

`CrxSensorDecoder` selects lossless or C-RAW using `CMP1` and emits rows with a sensor-space origin `(x, y)`. Successive values in that row belong at `x + 2*i`. Plane indices describe the four positions in a 2 × 2 grid, and the CFA layout identifies the color at each position. One reader task owns each active plane's state and its reusable row buffers. A row consumer reads the row within the callback and can write it directly into a destination raster. [Tile order, position planes and sensor output](format.md#tile-order-position-planes-and-sensor-output)

### Lossless prediction and entropy

`CrxNativeLosslessImage` validates the V1 lossless layout, with one subband per tile plane. `CrxNativeLosslessPlane` reads the opening literal and predicts the rest of the first row from the preceding sample. Later rows use the median edge detector (MED) with left, above and above-left samples. A flat neighborhood can select a run of copied values. A run ending inside the row uses the value above as the prediction for its interruption sample. Rice symbols are unsigned, so the decoder unfolds their sign before adding the residual to the prediction. The adaptive Rice width and run index persist across rows within the same subband. Reconstructed lossless samples must fit `0..16383`. [Lossless profile and entropy boundaries](format.md#profile-and-entropy-boundaries) · [Residuals and adaptive state](format.md#unsigned-symbols-signed-residuals-and-state) · [Row and run rules](format.md#first-row-and-subsequent-rows)

### C-RAW coefficients and synthesis

`CrxLossyDecoder` validates the measured V1 one- and two-tile, three-level layouts. Each tile plane starts with the signed approximation band `LL3`, followed by `X`, `Y` and `XY` details from coarsest to finest. An entropy window excludes the band's declared terminal padding. The approximation reuses the lossless predictor with a signed 21-bit coefficient range. `CrxWaveletDetail` decodes its own adaptive Rice and run state, using quantized coefficients for context. It multiplies each signed detail coefficient by the inverse quantization factor before synthesis. [C-RAW profile and band windows](format.md#profile-and-band-windows) · [Approximation and detail entropy](format.md#approximation-band-and-detail-entropy) · [Inverse quantization](format.md#inverse-quantization)

`CrxWaveletTile` reads the physical band rows, including tile-boundary support columns. It synthesizes each level horizontally and then vertically using integer 5/3 lifting. A tile uses its stored support at the shared horizontal seam. At an outer edge, the lift repeats the nearest available value when a neighboring detail or even sample is missing. Some stored low-X detail rows are wider than the synthesis support, so the entropy reader still consumes the surplus columns. Intermediate coefficients stay signed. After the final lift, `CrxLossyDecoder` clamps the recovered samples to `0..16383` and emits the same Bayer row layout as the lossless path. The result represents the sensor mosaic reconstructed from the quantized capture. [Physical band dimensions and tile boundaries](format.md#physical-band-dimensions-and-tile-boundaries) · [Inverse integer 5/3 lifting](format.md#inverse-integer-53-lifting)

The V2 single-tile profile adds an adaptive quantization map. Four plane readers
share one byte per map cell and continue to reconstruct the bands with bounded
row buffers. Every band still has its own compressed input window and entropy
state. See the [measured adaptive format and coverage](../cr3-results/2026-11-22-crx-adaptive/README.md).

Most classes under `com.photographer.focusstack.cr3.raw.codec` are internal even when Java visibility is public for module integration and tests. `Cr3RawImage` is `PUBLIC_EXPERIMENTAL`.

### Workers, memory and partial reads

`CrxTileDecoder` can schedule separate Bayer positions in parallel and preserve left-to-right tile order within each position. A consumer with disjoint writes can also allow independent tile planes. Processing with a shared optical-black accumulator preserves the tile order when the masked columns span multiple tiles. `Cr3Workers` shares a CPU pool across parallel stages. The per-read `maxWorkers` cap can reduce live buffers, with zero selecting automatically and one requesting serial work.

Lossless decoding keeps two reconstructed rows and one input window per active plane. C-RAW decoding keeps per-band input windows and the rows needed for lifting. The facade allocates or reuses a raster for the requested output. C-RAW and lossless reads can skip unused tile planes and decode the required row prefixes, including prerequisite rows for prediction or lifting. A region read validates the full directory and decodes the needed entropy. Use a full-frame read to exercise every tile plane. RGB region reads also retain neighboring Bayer sites for demosaic and may read extra rows for optical-black calibration.

Worker calls wait for submitted tasks before returning or propagating an error. Interruption and ImageIO's abort flag do not stop the codec mid-read. A caller-owned destination used for decode can hold partial results if the decode fails.

## Internal processing layer

`cr3-processing` applies post-codec operations:

- pedestal/optical-black handling;
- active-area crop;
- Bayer demosaic;
- white balance;
- camera color matrix and dual-illuminant interpolation;
- linear-to-sRGB conversion;
- orientation transforms.

These classes implement the internal CR3 rendering pipeline. The facade resolves the display pedestal from optical-black rows when available, then MakerNote per-channel values, then a global default. White balance uses valid CTMD gains when present and can fall back to MakerNote or daylight multipliers. The RGB path applies the requested black-level and white-balance corrections before demosaic, followed by the camera color matrix, sRGB conversion and EXIF orientation. [Capture calibration and output modes](format.md#cfa-and-calibration-in-output-modes)

On supported RAW and C-RAW files, `ImageIO.read(File)` returns a full-resolution sRGB image with three unsigned 16-bit bands. This is the ImageIO default since 0.1.9. The core factory `Cr3DecodeOptions.rawProcessed()` selects RGB8. For explicit RGB16 use `rawProcessed().bitDepth(Cr3DecodeOptions.BitDepth.SIXTEEN)`. RGB16 normalization, interpolation and color conversion retain fractional values through the final clamp and rounding to `0..65535`. Disabling sRGB conversion selects linear RGB. [ImageIO sample access and options](imageio.md) · [0.1.9 release notes](../releases/0.1.9.md)

### Optical correction and camera tone

Processed-RAW factories enable supported lens correction and camera tone by
default. Each stage remains independently selectable through
`applyLensCorrection(false)` and `applyCameraTone(false)`. Linear RGB skips the
display tone curve. Profile matching and the evaluated body/Picture Style set
still determine whether a stage applies.

Optical correction and camera tone remain sequential operations. Region and
subsampled reads correct the full optical field, select the requested samples,
and then tone only that result. A combined final-channel transfer was evaluated
but rejected after complete-import regressions; it is not an active runtime path.
[Optical processing](../../canon-cr3-processing/src/main/java/com/photographer/focusstack/cr3/processing/OpticalCorrection.java)

The heap planner reserves the shared tone tables (1,835,008 array-payload bytes)
alongside processing workspace and later copies. This existing table storage
was previously omitted from the shared reserve. The estimate does not initialize
it, and conservatively reserves first use even when optical scratch can be
reclaimed before tone or the tables were already initialized. Only the encoder
is deducted from optical workspace because that workspace already includes it.
Tone-disabled, linear and Bayer reads receive no tone-table reserve. Worker
limits remain automatic and CPU-derived, subject to the existing memory policy.
[Complete-import evidence and reproduction](optical-tone-performance-2026-11-25/README.md)

### Caller-supplied RGB16 storage

For EXIF orientations 5–8, source columns become destination rows. Normal-memory RGB16 development visits up to 32 neighboring source columns over 128 rows at a time. The next scanline revisits the same destination cache lines. Each tile includes a one-column interpolation halo, and the shared normalization code uses the original sensor coordinates for black levels and white balance. [Transposed RGB16 development](../../canon-cr3-processing/src/main/java/com/photographer/focusstack/cr3/processing/TransposedRgb16Rows.java)

A full-frame ImageIO read can explicitly lend the decoder three dense unsigned-short banks in logical R, G, B order. For EXIF orientation 1, CRX reconstruction writes the native Bayer mosaic into the destination's green bank. Before development overwrites that bank, the developer captures both neighboring sensor rows at every worker-stripe boundary. For other orientations the decoder allocates a separate Bayer plane and writes the oriented RGB samples into the supplied banks. Heap planning accounts for the input plane required by the orientation. The caller owns the destination throughout the read, including after a decode failure. [ImageIO usage](imageio.md#reuse-an-rgb16-destination) · [RGB16 developer](../../canon-cr3-processing/src/main/java/com/photographer/focusstack/cr3/processing/Rgb16Developer.java)

### Compact RGB16 orientation

When a full RGB16 read uses the low-memory path, the decoder develops the image in sensor orientation and reuses its owned Bayer array as the green channel. EXIF orientations 5–8 exchange the axes. The compact transform builds a new raster over the same three channel arrays, so only the returned raster has the final dimensions. It also handles orientation of an RGB16 subsampling result. [Decoder](../../canon-cr3-core/src/main/java/com/photographer/focusstack/cr3/decode/Cr3Decoder.java) · [Orientation dispatch](../../canon-cr3-processing/src/main/java/com/photographer/focusstack/cr3/processing/ExifOrientationTransformer.java)

For dense, independently owned unsigned-short banks, the transform groups adjacent source columns. It saves any partial group at the output edge, permutes whole runs into destination order using one visited bit per group, then transposes each group through a reusable short buffer. The same buffer and bitmap serve all three bands. For source width `W`, height `H` and group width `g`, the buffer holds `g × H` shorts and the bitmap tracks `floor(W / g) × H` groups. The largest fitting width among 64, 32, 16 and 8 keeps both array payloads plus a 1 KiB overhead reserve within the former pixel-visited bitmap budget, rounded to whole 64-bit words. For the 5496 × 3668 G5 frame, a 64-column group uses 508,480 bytes of temporary array payload versus 2,519,920 bytes for the pixel bitmap. Images shorter than eight rows and shapes for which no group fits use pixel-wise permutation cycles. Non-dense or shared layouts use the general orientation transform. [Group permutation and budget](../../canon-cr3-processing/src/main/java/com/photographer/focusstack/cr3/processing/CompactRgb16Transpose.java) · [Sample equality and bank ownership tests](../../canon-cr3-processing/src/test/java/com/photographer/focusstack/cr3/processing/ExifOrientationCompactTest.java)

### Sparse RGB16 sampling

ImageIO specifies a source region, spacing and offsets in oriented image coordinates. The decoder maps the first and last selected sites back to the sensor crop and interchanges the spacing axes for EXIF orientations 5–8. Each selected site needs its full-resolution neighbors for bilinear interpolation, including sites immediately outside the requested rectangle when they exist. [ImageIO regions and destinations](imageio.md#standard-imageio-regions-and-destinations)

When an axis contains at least two selected sites spaced five or more sensor positions apart, the RGB16 decoder can discard gaps between their neighborhoods while collecting Bayer rows. Along each axis with gaps it keeps three positions per interval for odd spacing and four for even spacing. Thus a step of eight becomes four in the stored mosaic, while a step of five becomes three. Removing an even number of coordinates preserves the Bayer color at every retained site. Crop margins and real sensor edges still determine which neighbors contribute. The heap estimate accounts for the stored mosaic dimensions and any projected row-calibration table. [Sample grid and memory planning](../../canon-cr3-core/src/main/java/com/photographer/focusstack/cr3/decode/Cr3Decoder.java)

CRX plane readers still reconstruct the row prefixes their predictors or wavelet filters need. The decoder feeds the masked sensor columns to the optical-black estimator before dropping gaps. White normalization uses the complete optical-black calibration. When retained rows have gaps, the decoder builds a pedestal table for those rows using their original sensor coordinates and Bayer phase. This preserves row-to-row differences in black level. The ImageIO regression compares every returned channel against the corresponding pixel of a full read across EXIF orientations, source offsets, crops, and linear and sRGB transfers. [Per-row calibration](../../canon-cr3-processing/src/main/java/com/photographer/focusstack/cr3/processing/BlackLevel.java) · [ImageIO comparison test](../../canon-cr3-imageio/src/test/java/com/photographer/focusstack/cr3/imageio/Cr3RgbSubsamplingTest.java) · [Reproduce the import benchmarks](rgb16-import-2026-11-24.md#reproduce-the-rgb16-measurements)

## Unified facade

For a runnable Maven consumer that reads files by path, develops RGB or selects a sensor region, see [Import a CR3 file](import.md).

`cr3-core` composes container, metadata, codec and processing into:

- `Cr3Decoder`;
- `Cr3DecodeOptions`;
- evolving pedestal/diagnostic reports.

### Output modes

| Mode | Status | Behavior |
|---|---|---|
| `PREVIEW` | Stable subset | Embedded JPEG preview, the default for the standalone `Cr3Decoder` |
| `THUMBNAIL` | Stable subset | Embedded JPEG thumbnail |
| `RAW_LINEAR` | Verified for the RG4 lossless and RG5 C-RAW layouts | One unsigned-short Bayer band scaled toward the 16-bit range, with optional crop and default black-level subtraction |
| `RAW_SENSOR` | Verified on the same profiles | Unscaled sensor codes in one unsigned-short band, including optical-black margins |
| `RAW_PROCESSED` | Experimental | RAW decode plus crop/demosaic/color processing; 8-bit by default, optional 16-bit RGB from 0.1.8 |

Factory shortcuts include `Cr3DecodeOptions.defaults()`, `rawLinear()`, `rawSensor()` and `rawProcessed()`.

Standard ImageIO image reads use `RAW_PROCESSED`, which yields an RGB image on supported layouts. ImageIO `readRaster` uses scaled `RAW_LINEAR` values by default. For native high-bit Bayer values, set `Cr3DecodeOptions.rawSensor()` on a `CanonCr3ImageReadParam` and pass it to `readRaster` or `read`. A Bayer raster has one sample at each site. Its color comes from the CFA pattern and source coordinates. [ImageIO read modes and examples](imageio.md#run-a-selected-read-mode)

ImageIO's sensor calibration metadata records the capture geometry and the available CTMD gain, black and white values. The gains and levels accompany sensor data and can be carried into a Bayer DNG. The developed image uses the facade's resolved processing parameters. For example, its normalization white level is derived from the nominal sensor maximum and the minimum subtracted black level. [CTMD ColorData](format.md#ctmd-colordata) · [Bayer DNG round trip](imageio.md#write-and-read-a-bayer-dng)

`experimentalPedestal(int)` and `EXPERIMENTAL_REFERENCE_MATCH` are research-only controls. They must not appear in production examples or compatibility promises.

## Full-sensor verification

Use `rawSensor()` to read the reconstructed sensor samples in native units. On the verified profiles a returned value of 2048 means 2048 in the camera's 14-bit range. A full read includes the optical-black margins and keeps sensor orientation. `rawLinear()` can crop or subtract the default black level before scaling and still yields Bayer samples in sensor orientation.

```java
try (var decoder = new Cr3Decoder()) {
    decoder.setInput(rawFile);
    BufferedImage sensor = decoder.decode(Cr3DecodeOptions.rawSensor());
    int nativeSample = sensor.getRaster().getSample(x, y, 0);
}
```

The whole-sensor integration tests compare `RAW_LINEAR` with the reference after reversing the scaling. Add `-Dcr3.full-sensor.native=true` to the same Maven command to compare `RAW_SENSOR` directly. Both modes use the same registered CR3 files, reference rasters and exact comparison at every sensor site.

The C-RAW gate checks equality with the native reconstruction of the compressed capture, including optical black, both sides of the tile seam and saturated sites. Other CRX layouts, RAW bursts, additional sensor depths, and camera-specific color rendering have separate qualification criteria.

## Research promotion gate

A production semantic change requires:

1. a pre-stated hypothesis based on a permissible source or project-owned observation;
2. fixed inputs and manifest-bound references;
3. quantitative improvement on the specified primary metrics;
4. no regression across camera-family and physical plausibility gates;
5. a durable result document with accepted/rejected status;
6. clean-room contributor evidence;
7. a separate production change and regression test.

Broad sweeps selected by rendered appearance are not an acceptable promotion method.

## Build and verification

```bash
mvn -B verify -pl canon-cr3-container -am -Djava.awt.headless=true
mvn -B verify -pl canon-cr3-metadata -am -Djava.awt.headless=true
mvn -B verify -pl canon-cr3-codec -am -Djava.awt.headless=true
mvn -B verify -pl canon-cr3-processing -am -Djava.awt.headless=true
mvn -B clean verify -pl canon-cr3-core -am -Djava.awt.headless=true
```

The experimental codec gate proves current regression contracts, not universal correctness.

## Related documentation

- [CR3 overview](index.md)
- [CR3 and CRX format reference](format.md)
- [ImageIO](imageio.md)
- [Current state](current-state.md)
- [Research process](research.md)
- [API boundaries](../cr3-api-boundaries.md)
- [Clean-room record](../cleanroom/README.md)
- [Container README](../../canon-cr3-container/README.md)
- [Metadata README](../../canon-cr3-metadata/README.md)
- [Codec README](../../canon-cr3-codec/README.md)
- [Processing README](../../canon-cr3-processing/README.md)
- [Core README](../../canon-cr3-core/README.md)
