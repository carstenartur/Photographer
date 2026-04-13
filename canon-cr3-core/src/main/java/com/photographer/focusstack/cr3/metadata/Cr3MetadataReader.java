package com.photographer.focusstack.cr3.metadata;

import com.photographer.focusstack.cr3.bmff.IsoBox;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Track;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.util.Map;

/**
 * Reads metadata from CR3 files independently of image data decoding.
 *
 * <p>Extracts information from:</p>
 * <ul>
 *   <li>CMT1 — TIFF IFD0 (camera make/model, orientation, date)</li>
 *   <li>CMT2 — EXIF IFD (ISO, exposure, aperture, focal length)</li>
 *   <li>CMT3 — Canon MakerNote (presence logged only; proprietary structure)</li>
 *   <li>CMT4 — GPS IFD (presence detected; tags not yet persisted into metadata)</li>
 * </ul>
 */
public final class Cr3MetadataReader {

    private static final Logger LOG = LoggerFactory.getLogger(Cr3MetadataReader.class);

    private Cr3MetadataReader() {}

    public static Cr3Metadata read(ImageInputStream input, Cr3Container container)
            throws IOException {
        Cr3Metadata metadata = new Cr3Metadata();
        metadata.setCameraMake("Canon");

        // Set sensor dimensions and bit depth from RAW track if available
        for (var track : container.tracks()) {
            if (track.type() == Cr3Track.TrackType.CRX_RAW
                    || track.type() == Cr3Track.TrackType.CRX_BIG) {
                metadata.setSensorWidth(track.width());
                metadata.setSensorHeight(track.height());
                if (track.cmp1() != null) {
                    metadata.setBitDepth(track.cmp1().bitsPerSample());
                }
                if (track.iad1() != null) {
                    int sW = track.width();
                    int sH = track.height();
                    // Clamp active area to sensor bounds (some firmware produces overflow values)
                    metadata.setActiveAreaLeft(Math.max(0, Math.min(track.iad1().activeLeft(), sW)));
                    metadata.setActiveAreaTop(Math.max(0, Math.min(track.iad1().activeTop(), sH)));
                    metadata.setActiveAreaRight(Math.max(0, Math.min(track.iad1().activeRight(), sW)));
                    metadata.setActiveAreaBottom(Math.max(0, Math.min(track.iad1().activeBottom(), sH)));
                }
                break;
            }
        }

        // Set compressor version from CNCV box
        if (container.cncvBox() != null) {
            metadata.setCompressorVersion(container.cncvBox().versionString());
        }

        // Parse CMT boxes from moov
        if (container.moovBox() != null) {
            readCmtBoxes(input, container.moovBox(), metadata);
        }

        LOG.debug("Extracted metadata: {}", metadata);
        return metadata;
    }

    private static void readCmtBoxes(ImageInputStream input, IsoBox moovBox,
                                      Cr3Metadata metadata) {
        // CMT1: TIFF IFD0 (camera make, model, date)
        IsoBox cmt1 = moovBox.findDescendant("CMT1");
        if (cmt1 != null) {
            LOG.debug("Found CMT1 (EXIF IFD0) at offset {}, size {}", cmt1.offset(), cmt1.size());
            try {
                parseCmt1(input, cmt1, metadata);
            } catch (IOException e) {
                LOG.warn("Failed to parse CMT1: {}", e.getMessage());
            }
        }

        // CMT2: EXIF IFD (ISO, exposure, aperture, focal length)
        IsoBox cmt2 = moovBox.findDescendant("CMT2");
        if (cmt2 != null) {
            LOG.debug("Found CMT2 (EXIF ExifIFD) at offset {}, size {}", cmt2.offset(), cmt2.size());
            try {
                parseCmt2(input, cmt2, metadata);
            } catch (IOException e) {
                LOG.warn("Failed to parse CMT2: {}", e.getMessage());
            }
        }

        // CMT3: Canon MakerNote
        IsoBox cmt3 = moovBox.findDescendant("CMT3");
        if (cmt3 != null) {
            LOG.debug("Found CMT3 (Canon MakerNote) at offset {}, size {}", cmt3.offset(), cmt3.size());
            // MakerNote has a proprietary internal structure;
            // we only log its presence for now
        }

        // CMT4: GPS IFD
        IsoBox cmt4 = moovBox.findDescendant("CMT4");
        if (cmt4 != null) {
            LOG.debug("Found CMT4 (GPS IFD) at offset {}, size {}", cmt4.offset(), cmt4.size());
            try {
                parseCmt4(input, cmt4, metadata);
            } catch (IOException e) {
                LOG.warn("Failed to parse CMT4: {}", e.getMessage());
            }
        }
    }

    private static void parseCmt1(ImageInputStream input, IsoBox cmt1,
                                   Cr3Metadata metadata) throws IOException {
        Map<Integer, TiffIfdParser.TagValue> tags =
                TiffIfdParser.parseIfd(input, cmt1.dataOffset(), cmt1.dataSize());

        if (tags.containsKey(TiffIfdParser.TAG_MAKE)) {
            if (tags.get(TiffIfdParser.TAG_MAKE) instanceof TiffIfdParser.TagValue.StringValue sv) {
                metadata.setCameraMake(sv.value());
            }
        }
        if (tags.containsKey(TiffIfdParser.TAG_MODEL)) {
            if (tags.get(TiffIfdParser.TAG_MODEL) instanceof TiffIfdParser.TagValue.StringValue sv) {
                metadata.setCameraModel(sv.value());
            }
        }
        if (tags.containsKey(TiffIfdParser.TAG_DATETIME)) {
            if (tags.get(TiffIfdParser.TAG_DATETIME) instanceof TiffIfdParser.TagValue.StringValue sv) {
                metadata.setDateTaken(sv.value());
            }
        }
    }

    private static void parseCmt2(ImageInputStream input, IsoBox cmt2,
                                   Cr3Metadata metadata) throws IOException {
        Map<Integer, TiffIfdParser.TagValue> tags =
                TiffIfdParser.parseIfd(input, cmt2.dataOffset(), cmt2.dataSize());

        if (tags.containsKey(TiffIfdParser.TAG_ISO_SPEED)) {
            if (tags.get(TiffIfdParser.TAG_ISO_SPEED) instanceof TiffIfdParser.TagValue.IntValue iv) {
                metadata.setIsoSpeed(iv.value());
            }
        }
        if (tags.containsKey(TiffIfdParser.TAG_EXPOSURE_TIME)) {
            if (tags.get(TiffIfdParser.TAG_EXPOSURE_TIME) instanceof TiffIfdParser.TagValue.RationalValue rv) {
                metadata.setExposureTime(rv.toDouble());
                if (rv.denominator() != 0) {
                    metadata.setShutterSpeed(rv.numerator() + "/" + rv.denominator());
                }
            }
        }
        if (tags.containsKey(TiffIfdParser.TAG_FNUMBER)) {
            if (tags.get(TiffIfdParser.TAG_FNUMBER) instanceof TiffIfdParser.TagValue.RationalValue rv) {
                metadata.setFNumber(rv.toDouble());
                metadata.setAperture("f/%.1f".formatted(rv.toDouble()));
            }
        }
        if (tags.containsKey(TiffIfdParser.TAG_FOCAL_LENGTH)) {
            if (tags.get(TiffIfdParser.TAG_FOCAL_LENGTH) instanceof TiffIfdParser.TagValue.RationalValue rv) {
                double mm = rv.toDouble();
                metadata.setFocalLengthMm(mm);
                metadata.setFocalLength("%.0fmm".formatted(mm));
            }
        }
        if (tags.containsKey(TiffIfdParser.TAG_DATE_TIME_ORIGINAL)) {
            if (tags.get(TiffIfdParser.TAG_DATE_TIME_ORIGINAL) instanceof TiffIfdParser.TagValue.StringValue sv) {
                metadata.setCaptureDateTime(sv.value());
                if (metadata.dateTaken().isEmpty()) {
                    metadata.setDateTaken(sv.value());
                }
            }
        }
    }

    private static void parseCmt4(ImageInputStream input, IsoBox cmt4,
                                   Cr3Metadata metadata) throws IOException {
        Map<Integer, TiffIfdParser.TagValue> tags =
                TiffIfdParser.parseIfd(input, cmt4.dataOffset(), cmt4.dataSize());
        // GPS tags are parsed here only to detect and report their presence.
        // They are not currently persisted into Cr3Metadata.
        if (!tags.isEmpty()) {
            LOG.debug("Parsed {} GPS tags from CMT4", tags.size());
        }
    }
}

