package com.photographer.focusstack.cr3.imageio;

import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;

import javax.imageio.ImageTypeSpecifier;
import java.awt.Rectangle;
import java.awt.image.BandedSampleModel;
import java.awt.image.ComponentSampleModel;
import java.awt.image.DataBuffer;
import java.awt.image.MultiPixelPackedSampleModel;
import java.awt.image.SampleModel;
import java.awt.image.SinglePixelPackedSampleModel;
import java.util.Arrays;

/** Estimates a later ImageIO copy without allocating pixels or invoking a custom image factory. */
final class Cr3DestinationMemory {
    private Cr3DestinationMemory() {}

    private static boolean standardLayout(ImageTypeSpecifier type) {
        Module desktop = ImageTypeSpecifier.class.getModule();
        return type.getClass().getModule() == desktop && type.getSampleModel().getClass().getModule() == desktop;
    }

    /** A matching RGB16 request can return the decoded banded raster directly. */
    static boolean matches(ImageTypeSpecifier requested, ImageTypeSpecifier developed, int width, int height) {
        if (!standardLayout(requested)) {
            return false;
        }
        try {
            return requested.getColorModel().equals(developed.getColorModel())
                    && requested.getSampleModel(width, height).equals(developed.getSampleModel(width, height));
        } catch (IllegalArgumentException invalidSize) {
            // The actual destination negotiation and its errors stay in their original phase.
            return false;
        }
    }

    static Cr3DecodeOptions forCopy(Cr3DecodeOptions options, ImageTypeSpecifier type, Rectangle output, int bands) {
        long extra = Long.MAX_VALUE;
        if (standardLayout(type)) {
            try {
                // Include the working row WritableRaster.setRect can use while copying the output.
                long row = (long) output.width * bands * Integer.BYTES;
                SampleModel model = type.getSampleModel(output.x + output.width, output.y + output.height);
                long payload = storageBytes(model);
                extra = Math.addExact(Math.addExact(options.postDecodeMemoryBytes(), payload), row);
            } catch (IllegalArgumentException | ArithmeticException invalidSize) {
                // An unrepresentable estimate must not appear to fit. The factory itself runs after decode.
            }
        }
        return options.copy().postDecodeMemoryBytes(extra);
    }

    static long storageBytes(SampleModel model) {
        long width = model.getWidth(), height = model.getHeight();
        long elementBytes = DataBuffer.getDataTypeSize(model.getDataType()) / Byte.SIZE;
        long elements;
        if (model instanceof SinglePixelPackedSampleModel packed) {
            elements = (height - 1) * packed.getScanlineStride() + width;
        } else if (model instanceof ComponentSampleModel channels) {
            int bandOffset = Arrays.stream(channels.getBandOffsets()).max().orElseThrow();
            long banks = 1L + Arrays.stream(channels.getBankIndices()).max().orElseThrow();
            // A sparse index space can require arbitrarily many tiny array objects and
            // references even when the raster has only a few samples and channels.
            if (banks > channels.getNumBands()) {
                return Long.MAX_VALUE;
            }
            long bankSize = channels instanceof BandedSampleModel
                    ? height * channels.getScanlineStride() + bandOffset
                    : (height - 1) * channels.getScanlineStride() + (width - 1) * channels.getPixelStride()
                            + bandOffset + 1;
            elements = Math.multiplyExact(bankSize, banks);
        } else if (model instanceof MultiPixelPackedSampleModel packed) {
            long offsetElements = Math.ceilDiv(packed.getDataBitOffset(), elementBytes * Byte.SIZE);
            elements = height * packed.getScanlineStride() + offsetElements;
        } else {
            return Long.MAX_VALUE;
        }
        return Math.multiplyExact(elements, elementBytes);
    }
}
