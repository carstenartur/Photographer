package com.photographer.focusstack.cr3.imageio;

import com.photographer.focusstack.cr3.container.Cr3Parser;

import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.util.Locale;

/**
 * Service provider for Canon CR3 image reading via {@code javax.imageio}.
 *
 * <p>The standard ImageIO call develops a full-resolution color image from CR3 RAW data.</p>
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
                new String[]{CanonCr3ImageWriterSpi.class.getName()}, // writerSpiNames
                false,                     // supportsStandardStreamMetadataFormat
                null,                      // nativeStreamMetadataFormatName
                null,                      // nativeStreamMetadataFormatClassName
                null,                      // extraStreamMetadataFormatNames
                null,                      // extraStreamMetadataFormatClassNames
                false,                     // supportsStandardImageMetadataFormat
                Cr3ImageMetadata.NATIVE_FORMAT_NAME, // nativeImageMetadataFormatName
                Cr3MetadataFormat.class.getName(),
                null,                      // extraImageMetadataFormatNames
                null                       // extraImageMetadataFormatClassNames
        );
    }

    @Override
    public boolean canDecodeInput(Object source) throws IOException {
        return source instanceof ImageInputStream input && Cr3Parser.isCr3(input);
    }

    @Override
    public ImageReader createReaderInstance(Object extension) {
        return new CanonCr3ImageReader(this);
    }

    @Override
    public String getDescription(Locale locale) {
        return "Independent Java ImageIO reader for CR3 files produced by Canon cameras.";
    }
}
