package com.photographer.focusstack.cr3.decode;

import com.photographer.focusstack.cr3.processing.BlackLevel;
import com.photographer.focusstack.cr3.raw.Cr3RawImage;

/**
 * Active-area Bayer data and resolved parameters for the default RAW color development.
 * The caller owns the image and parameter arrays. The Bayer values have their native
 * bit depth and have yet to undergo black-level correction or white balance.
 *
 * @param image cropped Bayer samples in sensor orientation
 * @param blackLevel black level in sensor coordinates
 * @param sensorX sensor column of the first sample
 * @param sensorY sensor row of the first sample
 * @param whiteBalance red, green and blue multipliers
 * @param whiteLevel normalization level after black-level subtraction
 * @param colorMatrix camera-to-linear-sRGB matrix, applied before sRGB encoding
 * @param orientation EXIF orientation of the developed image
 */
public record Cr3PreparedRaw(Cr3RawImage image, BlackLevel blackLevel, int sensorX, int sensorY,
                            double[] whiteBalance, int whiteLevel, double[][] colorMatrix, int orientation) {}
