package com.photographer.focusstack.cr3.imageio;

import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.ImageOutputStream;
import java.awt.color.ColorSpace;
import java.awt.image.DataBuffer;
import java.util.Locale;

/** ImageIO provider for lossless CR3 writing with a selected camera reference. */
public final class CanonCr3ImageWriterSpi extends ImageWriterSpi {
    public CanonCr3ImageWriterSpi() {
        super("Photographer", "1.0", new String[]{"CR3", "cr3", "canon-cr3"}, new String[]{"cr3", "CR3"},
                new String[]{"image/x-canon-cr3"}, CanonCr3ImageWriter.class.getName(),
                new Class<?>[]{ImageOutputStream.class}, new String[]{CanonCr3ImageReaderSpi.class.getName()},
                false, null, null, null, null,
                false, null, null, null, null);
    }

    @Override
    public boolean canEncodeImage(ImageTypeSpecifier type) {
        var samples = type.getSampleModel();
        return type.getColorModel().getColorSpace().getType() == ColorSpace.TYPE_GRAY
                && samples.getNumBands() == 1 && samples.getTransferType() == DataBuffer.TYPE_USHORT;
    }

    @Override
    public ImageWriter createWriterInstance(Object extension) {
        return new CanonCr3ImageWriter(this);
    }

    @Override
    public String getDescription(Locale locale) {
        return "Lossless CR3 writer using a camera reference";
    }
}
