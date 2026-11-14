package com.photographer.focusstack.dng.imageio;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Locale;
import javax.imageio.ImageReader;
import javax.imageio.plugins.tiff.TIFFTag;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.spi.ServiceRegistry;
import javax.imageio.stream.ImageInputStream;

/** Discovers classic TIFF-based DNG files with a single unsigned 16-bit Bayer image in IFD0. */
public final class DngImageReaderSpi extends ImageReaderSpi {
    public DngImageReaderSpi() {
        super("Photographer", "1.0", new String[]{"DNG", "dng"}, new String[]{"dng"},
                new String[]{"image/x-adobe-dng"}, DngImageReader.class.getName(),
                new Class<?>[]{ImageInputStream.class},
                new String[]{"com.photographer.focusstack.dng.imageio.DngImageWriterSpi"},
                false, null, null, null, null,
                false, DngImageMetadata.NATIVE_FORMAT_NAME, null, null, null);
    }

    @Override
    public void onRegistration(ServiceRegistry registry, Class<?> category) {
        // Generic TIFF providers also recognize DNG's header; prefer the specialized provider.
        if (category != ImageReaderSpi.class) {
            return;
        }
        var readers = registry.getServiceProviders(ImageReaderSpi.class, false);
        while (readers.hasNext()) {
            var other = readers.next();
            if (Arrays.stream(other.getFormatNames()).anyMatch("TIFF"::equalsIgnoreCase)) {
                registry.setOrdering(ImageReaderSpi.class, this, other);
            }
        }
    }

    @Override
    public boolean canDecodeInput(Object source) throws IOException {
        if (!(source instanceof ImageInputStream input)) {
            return false;
        }
        ByteOrder savedOrder = input.getByteOrder();
        input.mark();
        try {
            long start = input.getStreamPosition();
            int marker = input.readUnsignedShort();
            if (marker != 0x4949 && marker != 0x4d4d) {
                return false;
            }
            input.setByteOrder(marker == 0x4949 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
            if (input.readUnsignedShort() != 42) {
                return false;
            }
            long offset = input.readUnsignedInt();
            if (offset < 8 || (input.length() >= 0 && offset > input.length() - start - 2)) {
                return false;
            }
            input.seek(start + offset);
            return isSupportedRawIfd(input);
        } catch (EOFException e) {
            return false;
        } finally {
            input.setByteOrder(savedOrder);
            input.reset();
        }
    }

    /** Probe only inline TIFF values; never decode or allocate image data during discovery. */
    private static boolean isSupportedRawIfd(ImageInputStream input) throws IOException {
        int count = input.readUnsignedShort();
        boolean dngVersion = false;
        boolean cfa = false;
        boolean sixteenBits = false;
        boolean bayerSize = false;
        boolean bayerPattern = false;
        byte[] value = new byte[4];
        for (int i = 0; i < count; i++) {
            int tag = input.readUnsignedShort();
            int type = input.readUnsignedShort();
            long elements = input.readUnsignedInt();
            if (type == TIFFTag.TIFF_SHORT && elements == 1) {
                int number = input.readUnsignedShort();
                input.readUnsignedShort();
                switch (tag) {
                    case 258 -> sixteenBits = number == 16;             // BitsPerSample
                    case 262 -> cfa = number == 32803;                  // PhotometricInterpretation
                    case 259, 277, 339 -> {                             // Compression, SamplesPerPixel, SampleFormat
                        if (number != 1) return false;
                    }
                    default -> { }
                }
            } else if (tag == 33421 && type == TIFFTag.TIFF_SHORT && elements == 2) {
                int rows = input.readUnsignedShort();
                int columns = input.readUnsignedShort();
                bayerSize = rows == 2 && columns == 2;
            } else {
                input.readFully(value);
                if (type == TIFFTag.TIFF_BYTE && elements == 4) {
                    if (tag == 50706) dngVersion = value[0] == 1;
                    if (tag == 33422) bayerPattern = true;
                }
            }
        }
        return dngVersion && cfa && sixteenBits && bayerSize && bayerPattern;
    }

    @Override
    public ImageReader createReaderInstance(Object extension) {
        return new DngImageReader(this);
    }

    @Override
    public String getDescription(Locale locale) {
        return "Uncompressed 16-bit Bayer DNG sensor mosaics";
    }
}
