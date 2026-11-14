package com.photographer.focusstack.dng.imageio;

import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.ImageOutputStream;
import java.awt.color.ColorSpace;
import java.awt.image.DataBuffer;
import java.awt.image.SampleModel;
import java.util.Locale;

/** ImageIO provider for uncompressed, single-band CFA Digital Negative images. */
public final class DngImageWriterSpi extends ImageWriterSpi {

    public DngImageWriterSpi() {
        super("Photographer", "1.0", new String[]{"DNG", "dng"}, new String[]{"dng"},
                new String[]{"image/x-adobe-dng"}, DngImageWriter.class.getName(),
                new Class<?>[]{ImageOutputStream.class}, new String[]{DngImageReaderSpi.class.getName()},
                false, null, null, null, null,
                false, null, null, null, null);
    }

    static boolean isSensorRaster(SampleModel model) {
        return model.getNumBands() == 1 && model.getDataType() == DataBuffer.TYPE_USHORT
                && model.getSampleSize(0) == 16;
    }

    @Override
    public boolean canEncodeImage(ImageTypeSpecifier type) {
        return type.getColorModel().getColorSpace().getType() == ColorSpace.TYPE_GRAY
                && isSensorRaster(type.getSampleModel());
    }

    @Override
    public ImageWriter createWriterInstance(Object extension) {
        return new DngImageWriter(this);
    }

    @Override
    public String getDescription(Locale locale) {
        return "Uncompressed Bayer DNG writer";
    }
}
