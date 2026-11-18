/**
 * CR3 decoding facade for embedded JPEGs, Bayer rasters and developed RGB images.
 *
 * <p>{@link com.photographer.focusstack.cr3.decode.Cr3Decoder} coordinates container
 * parsing, metadata, sample reconstruction and optional color processing. The output mode
 * selects the coordinate system and sample units. Native Bayer output uses sensor
 * coordinates and sensor codes. Developed RGB uses the selected crop and EXIF orientation.</p>
 */
package com.photographer.focusstack.cr3.decode;
