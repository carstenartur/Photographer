package com.photographer.focusstack.cr3.processing;

import com.photographer.focusstack.cr3.raw.Cr3RawImage;
import com.photographer.focusstack.cr3.raw.Cr3RawImage.BayerPattern;

import java.util.Objects;

/** Read-only access to borrowed native Bayer codes; exactly one backing array is populated. */
record BayerSamples(int width, int height, BayerPattern bayerPattern, int[] integers, short[] unsignedShorts) {
    static BayerSamples of(Cr3RawImage image) {
        return new BayerSamples(image.width(), image.height(), image.bayerPattern(), image.data(), null);
    }

    static BayerSamples of(short[] samples, int width, int height, BayerPattern pattern) {
        if (width < 1 || height < 1 || (long) width * height != samples.length) {
            throw new IllegalArgumentException("Bayer dimensions must be positive and match the sample count");
        }
        return new BayerSamples(width, height, Objects.requireNonNull(pattern), null, samples);
    }

    int sample(int index) {
        return unsignedShorts == null ? integers[index] : Short.toUnsignedInt(unsignedShorts[index]);
    }
}
