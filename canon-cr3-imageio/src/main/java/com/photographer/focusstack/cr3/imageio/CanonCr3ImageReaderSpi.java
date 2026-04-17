package com.photographer.focusstack.cr3.imageio;

import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Service provider for Canon CR3 image reading via {@code javax.imageio}.
 *
 * <p>This SPI enables transparent CR3 support through the standard ImageIO API:</p>
 * <pre>{@code
 * BufferedImage image = ImageIO.read(new File("photo.cr3"));
 * }</pre>
 *
 * <p>The provider is registered automatically via the
 * {@code META-INF/services/javax.imageio.spi.ImageReaderSpi} service loader file.</p>
 */
public class CanonCr3ImageReaderSpi extends ImageReaderSpi {

    private static final String VENDOR = "Focus Stack Project";
    private static final String VERSION = "0.1.3";
    private static final String READER_CLASS_NAME =
            "com.photographer.focusstack.cr3.imageio.CanonCr3ImageReader";
    private static final String[] FORMAT_NAMES = {"cr3", "CR3", "canon-cr3"};
    private static final String[] SUFFIXES = {"cr3", "CR3"};
    private static final String[] MIME_TYPES = {"image/x-canon-cr3"};

    /**
     * Constructs the SPI with metadata describing the CR3 format.
     */
    public CanonCr3ImageReaderSpi() {
        super(
                VENDOR,                    // vendorName
                VERSION,                   // version
                FORMAT_NAMES,              // names
                SUFFIXES,                  // suffixes
                MIME_TYPES,                // MIMETypes
                READER_CLASS_NAME,         // readerClassName
                new Class[]{ImageInputStream.class}, // inputTypes
                null,                      // writerSpiNames
                false,                     // supportsStandardStreamMetadataFormat
                null,                      // nativeStreamMetadataFormatName
                null,                      // nativeStreamMetadataFormatClassName
                null,                      // extraStreamMetadataFormatNames
                null,                      // extraStreamMetadataFormatClassNames
                false,                     // supportsStandardImageMetadataFormat
                Cr3ImageMetadata.NATIVE_FORMAT_NAME, // nativeImageMetadataFormatName
                "com.photographer.focusstack.cr3.imageio.Cr3ImageMetadata", // nativeImageMetadataFormatClassName
                null,                      // extraImageMetadataFormatNames
                null                       // extraImageMetadataFormatClassNames
        );
    }

    @Override
    public boolean canDecodeInput(Object source) throws IOException {
        if (!(source instanceof ImageInputStream input)) {
            return false;
        }

        input.setByteOrder(ByteOrder.BIG_ENDIAN);
        input.mark();
        try {
            // CR3 files start with an ftyp box
            if (input.length() >= 0 && input.length() < 12) {
                return false;
            }

            long size = Integer.toUnsignedLong(input.readInt());
            byte[] type = new byte[4];
            input.readFully(type);
            if (!"ftyp".equals(new String(type, StandardCharsets.US_ASCII))) {
                return false;
            }

            // Parse the full ftyp box to check major brand + compatible brands
            com.photographer.focusstack.cr3.bmff.IsoBox ftypIsoBox =
                    new com.photographer.focusstack.cr3.bmff.IsoBox("ftyp", size, 0);
            com.photographer.focusstack.cr3.bmff.FtypBox ftypBox =
                    com.photographer.focusstack.cr3.bmff.FtypBox.parse(input, ftypIsoBox);
            return ftypBox.isCr3();
        } catch (IOException e) {
            return false;
        } finally {
            input.reset();
        }
    }

    @Override
    public ImageReader createReaderInstance(Object extension) {
        return new CanonCr3ImageReader(this);
    }

    @Override
    public String getDescription(Locale locale) {
        return "Canon CR3 RAW Image Reader";
    }
}
