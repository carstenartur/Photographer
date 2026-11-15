package com.photographer.focusstack.cr3.encode;

import com.photographer.focusstack.cr3.raw.Cr3RawImage.BayerPattern;
import java.nio.ByteBuffer;
import java.util.List;

/** Observed full-frame, lossless 14-bit CR3 formats supported by the writer. */
public enum Cr3CameraProfile {
    EOS_R("Canon EOS R", "00", 4, false, 1, 49,
            new ColorDefaults(17, 512, 14008),
            fullRaw(6888, 4546, new Rect(156, 58, 6875, 4537),
                    new Rect(0, 0, 143, 4545), new Rect(144, 0, 6887, 45),
                    new Rect(144, 46, 6887, 4545)), 1),
    POWERSHOT_G5_X_MARK_II("Canon PowerShot G5 X Mark II", "01", 5, true, 1, 53,
            new ColorDefaults(19, 2048, 14335),
            fullRaw(5632, 3710, new Rect(132, 40, 5603, 3687),
                    new Rect(0, 0, 119, 3695), new Rect(120, 0, 5615, 27),
                    new Rect(120, 28, 5615, 3695)), 3),
    EOS_M50("Canon EOS M50", "00", 4, false, 1, 49,
            new ColorDefaults(16, 512, 11892),
            fullRaw(6288, 4056, new Rect(276, 48, 6275, 4047),
                    new Rect(0, 0, 263, 4055), new Rect(264, 0, 6287, 35),
                    new Rect(264, 36, 6287, 4055)), 1),
    EOS_RP("Canon EOS RP", "00", 5, true, 2, 51,
            new ColorDefaults(18, 512, 14558),
            fullRaw(6384, 4224, new Rect(132, 56, 6371, 4215),
                    new Rect(0, 0, 119, 4223), new Rect(120, 0, 6383, 43),
                    new Rect(120, 44, 6383, 4223)), 1);

    static final int BITS_PER_SAMPLE = 14;
    private static final int PREVIEW_WIDTH = 1620;
    private static final int PREVIEW_HEIGHT = 1080;
    private static final int THUMBNAIL_WIDTH = 160;
    private static final int THUMBNAIL_HEIGHT = 120;

    private final String model;
    private final String compressorVersion;
    private final int ctboEntries;
    private final boolean explicitImageSampleSizes;
    private final int embeddedImageFlag;
    private final int cameraSettingsWords;
    private final ColorDefaults color;
    private final RawLayout raw;
    private final RawLayout reducedRaw;

    Cr3CameraProfile(String model, String compressorRevision, int ctboEntries,
                     boolean explicitImageSampleSizes, int embeddedImageFlag, int cameraSettingsWords,
                     ColorDefaults color, RawLayout raw, int reducedCropLeft) {
        this.model = model;
        this.compressorVersion = "CanonCR3_001/" + compressorRevision + ".09.00/00.00.00";
        this.ctboEntries = ctboEntries;
        this.explicitImageSampleSizes = explicitImageSampleSizes;
        this.embeddedImageFlag = embeddedImageFlag;
        this.cameraSettingsWords = cameraSettingsWords;
        this.color = color;
        this.raw = raw;
        this.reducedRaw = reducedRaw(reducedCropLeft);
    }

    /** Returns the camera model as stored in CR3 metadata. */
    public String cameraModel() {
        return model;
    }

    /** Returns the width required for the full Bayer raster, including the masked margins. */
    public int sensorWidth() {
        return raw.width();
    }

    /** Returns the height required for the full Bayer raster, including the masked margins. */
    public int sensorHeight() {
        return raw.height();
    }

    /** Returns the Bayer pattern at the full raster's first sample. */
    public BayerPattern bayerPattern() {
        return BayerPattern.RGGB;
    }

    String compressorVersion() {
        return compressorVersion;
    }

    int ctboEntryCount() {
        return ctboEntries;
    }

    boolean explicitImageSampleSizes() {
        return explicitImageSampleSizes;
    }

    int jpegWidth() {
        return raw.crop().width();
    }

    int jpegHeight() {
        return raw.crop().height();
    }

    int previewWidth() {
        return PREVIEW_WIDTH;
    }

    int previewHeight() {
        return PREVIEW_HEIGHT;
    }

    int thumbnailWidth() {
        return THUMBNAIL_WIDTH;
    }

    int thumbnailHeight() {
        return THUMBNAIL_HEIGHT;
    }

    int previewFlag() {
        return embeddedImageFlag;
    }

    int thumbnailFlag() {
        return embeddedImageFlag;
    }

    int cameraSettingsWords() {
        return cameraSettingsWords;
    }

    ColorDefaults color() {
        return color;
    }

    RawLayout raw() {
        return raw;
    }

    RawLayout reducedRaw() {
        return reducedRaw;
    }

    private static RawLayout fullRaw(int width, int height, Rect crop, Rect leftBlack, Rect topBlack,
                                     Rect active) {
        return new RawLayout(width, height, width / 2, 0, List.of(crop, leftBlack, topBlack, active));
    }

    private static RawLayout reducedRaw(int cropLeft) {
        int width = 1624;
        var crop = new Rect(cropLeft, 0, cropLeft + PREVIEW_WIDTH - 1, PREVIEW_HEIGHT - 1);
        var active = new Rect(0, 0, width - 1, PREVIEW_HEIGHT - 1);
        return new RawLayout(width, PREVIEW_HEIGHT, width, 1, List.of(crop, active));
    }

    record ColorDefaults(int version, int black, int white) {}

    // IAD1 endpoints are inclusive and relative to the start of the full raster.
    record Rect(int left, int top, int right, int bottom) {
        int width() {
            return right - left + 1;
        }

        int height() {
            return bottom - top + 1;
        }

        void write(ByteBuffer target) {
            target.putShort((short) left).putShort((short) top).putShort((short) right).putShort((short) bottom);
        }
    }

    record RawLayout(int width, int height, int tileWidth, int cfa, List<Rect> regions) {
        private static final int PLANES = 4;
        private static final int DIRECTORY_RECORD_BYTES = 12;

        Rect crop() {
            return regions.getFirst();
        }

        byte[] cmp1Payload() {
            var payload = ByteBuffer.allocate(52);
            payload.putShort((short) 0xff00).putShort((short) 48);
            payload.putShort((short) 0x0100).putShort((short) 0);
            payload.putInt(width).putInt(height).putInt(tileWidth).putInt(height);
            payload.put((byte) BITS_PER_SAMPLE).put((byte) ((PLANES << 4) | cfa));
            payload.putShort((short) 0); // Type 0, zero wavelet levels, observed tile flags.
            payload.putInt(directorySize()).putInt(0);
            for (int plane = 0; plane < PLANES; plane++) {
                payload.putInt(0x01010000);
            }
            return payload.array();
        }

        byte[] iad1Payload() {
            var payload = ByteBuffer.allocate(16 + regions.size() * 8);
            payload.putInt(0).putShort((short) width).putShort((short) height);
            payload.putShort((short) 1).putShort((short) (tileCount() == 2 ? 2 : 0));
            payload.putShort((short) 1).putShort((short) 0);
            for (Rect region : regions) {
                region.write(payload);
            }
            return payload.array();
        }

        byte[] directory() {
            var directory = ByteBuffer.allocate(directorySize());
            for (int tile = 0; tile < tileCount(); tile++) {
                directoryRecord(directory, 0xff01, tile << 16);
                for (int plane = 0; plane < PLANES; plane++) {
                    directoryRecord(directory, 0xff02, (plane << 28) | 0x08000000);
                    directoryRecord(directory, 0xff03, 0x00200000);
                }
            }
            return directory.array();
        }

        private int tileCount() {
            return width / tileWidth;
        }

        private int directorySize() {
            // The observed one-tile directory ends with a zero u32.
            return tileCount() * (1 + 2 * PLANES) * DIRECTORY_RECORD_BYTES
                    + (tileCount() == 1 ? Integer.BYTES : 0);
        }

        private static void directoryRecord(ByteBuffer target, int marker, int flags) {
            // The entropy encoder fills the byte counts and subband padding once it writes each plane.
            target.putShort((short) marker).putShort((short) 8).putInt(0).putInt(flags);
        }
    }
}
