# cr3-core architecture

The Java facade accepts a file path or a seekable JDK `ImageInputStream`. It owns streams opened from a path. The caller owns a stream supplied to `setInput(ImageInputStream)`. The `cr3-imageio` module adapts this facade to `ImageReader` and provides SPI discovery.

| Module | Responsibility |
|---|---|
| `cr3-container` | Locate tracks, read CMP1 compression parameters and extract the embedded JPEG |
| `cr3-metadata` | Read the active sensor area, orientation and available correction parameters |
| `cr3-codec` | Parse the track directory, decode entropy and reconstruct lossless or C-RAW tile-plane rows |
| `cr3-processing` | Apply black level, white balance, bilinear demosaicing, color correction and orientation |
| `cr3-core` | Select the output mode and allocate its raster |

## RAW input and output storage

The codec validates track and subband extents before allocating the output raster. Each active subband on a seekable stream has a read-ahead window of at most 32 KiB. Its position is independent of the other subbands. This lets wavelet synthesis alternate between bands while keeping encoded input storage bounded. Callers of the byte-array codec API read directly from their array. An input stream supplied by a caller can have its own cache.

`RAW_SENSOR` and `RAW_LINEAR` write tile-plane rows into a single unsigned-short raster. A region or subsampling request reduces that raster to the selected sites. The codec still reads the preceding rows needed for reconstruction.

For `RAW_PROCESSED`, the facade collects masked left-border samples for pedestal estimation when the selected calibration requires them, including masked rows outside the requested crop. Black levels use sensor coordinates. White balance and interpolation use the local Bayer phase. A region is expressed in final, oriented image coordinates. The decoder maps it back to the active area and retains the interpolation neighbors around it.

| Output | Storage during development |
| --- | --- |
| Packed RGB8 | Demosaicing rewrites the packed Bayer `int[]` workspace with RGB pixels. It is decoder-owned unless the ImageIO caller explicitly supplies a compatible workspace. Color matrix and sRGB processing operate on the packed result. The ordinary axis-swapping orientation and some partial-image paths allocate a separate final output. |
| RGB16 | The codec emits native samples in borrowed `int[]` tile-plane rows; the core facade assembles the selected samples into a `short[]` mosaic. Processing produces three unsigned-short banks, retaining fractional values through color processing and quantizing each channel at the end. A complete identity-oriented read can reuse the native array as its green bank. |

Starting with 0.1.8, compact processing of a complete owned RGB16 image develops in sensor orientation, reuses the green bank, then orients the result in place. Axis-swapping orientations use a visited bit per output pixel. For ImageIO callers that need an additional destination after decode, the planner compares the decoding peak with the later coexistence of retained pixels, destination and copy scratch. An adapter can use `postDecodeMemoryBytes(long)` for known additional storage in that later phase. The Fiji menu adapter takes ownership of the normal RGB16 banks, and Fiji manages its own display memory. [Heap policy and storage estimates](../docs/cr3/import.md#limited-heap) · [ImageJ adapter](../docs/developer/modules.md#imagej-adapter)

## CPU parallelism

The shared decode pool derives its parallelism from `Runtime.getRuntime().availableProcessors()`. Applications can set a lower limit for an individual import with `Cr3DecodeOptions.maxWorkers(int)`. One worker processes the import sequentially. The native sensor paths can distribute disjoint tile-plane writes across workers. When optical-black accumulation spans tiles, callbacks for the same Bayer phase stay ordered across tiles. Each worker has independent entropy and row state. Files opened via `Path` use positional reads from the same handle as the metadata reader. Readers over a supplied `ImageInputStream` synchronize only the seek and refill of each input window.

The RGB stages can use more than four workers on large images. Packed RGB8 in-place demosaicing precomputes the RGB row at each stripe boundary and keeps the original Bayer row in place until both neighbors have finished. Packed color correction and sRGB conversion can process independent row ranges. All stages finish their writes before the next stage starts.

The embedded JPEG preview is available independently of sensor reconstruction. [Output modes and verified profiles](../docs/cr3/decoder.md) · [ImageIO read parameters](../docs/cr3/imageio.md)
