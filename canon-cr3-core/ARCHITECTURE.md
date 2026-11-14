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

`RAW_SENSOR` and `RAW_LINEAR` write tile-plane rows into a single unsigned-short raster. A region or subsampling request reduces that raster to the selected sites. Prediction and wavelet reconstruction still traverse the frame.

For `RAW_PROCESSED`, the codec writes the selected active area into a packed integer array. During the same pass the facade accumulates the masked left-border samples by sensor row and Bayer phase, including rows outside the crop. Black-level subtraction uses the original sensor coordinates. White balance and demosaicing use the crop-local coordinates established by the processing API.

The demosaicer renders into two temporary rows. Once interpolation no longer needs a Bayer row, its slot in the image array receives the finished RGB row. The resulting `TYPE_INT_RGB` image owns that array. Color correction, sRGB conversion, horizontal and vertical flips, and 180-degree rotation operate on it directly. EXIF transforms that exchange the axes allocate a destination image with swapped dimensions. An ImageIO region of processed RGB uses the fully developed, oriented image.

## CPU parallelism

The shared decode pool derives its parallelism from `Runtime.getRuntime().availableProcessors()`. Applications can set a lower limit for an individual import with `Cr3DecodeOptions.maxWorkers(int)`. One worker processes the import sequentially. The native sensor paths can distribute the four Bayer phases across workers. One worker owns a given phase across all tiles, including its optical-black sums. Each worker has independent entropy and row state. Files opened via `Path` use positional reads from the same handle as the metadata reader. Readers over a supplied `ImageInputStream` synchronize only the seek and refill of each input window.

The RGB stages can use more than four workers on large images. In-place demosaicing precomputes the RGB row at each stripe boundary and keeps the original Bayer row in place until both neighbors have finished. Packed color correction and sRGB conversion can process independent row ranges. All stages finish their writes before the next stage starts.

The embedded JPEG preview is available independently of sensor reconstruction. [Output modes and verified profiles](../docs/cr3/decoder.md) · [ImageIO read parameters](../docs/cr3/imageio.md)
