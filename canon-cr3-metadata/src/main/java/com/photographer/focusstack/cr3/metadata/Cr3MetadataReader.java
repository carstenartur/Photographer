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
 *   <li>CMT3 — Canon MakerNote (white balance, color temperature, shot info)</li>
 *   <li>CMT4 — GPS IFD (latitude, longitude, altitude)</li>
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
                    var geometry = track.iad1().toSensorCropGeometry(track.width(), track.height());
                    metadata.setActiveAreaLeft(geometry.activeLeft());
                    metadata.setActiveAreaTop(geometry.activeTop());
                    metadata.setActiveAreaRight(geometry.activeRight());
                    metadata.setActiveAreaBottom(geometry.activeBottom());
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
            try {
                parseCmt3(input, cmt3, metadata);
            } catch (IOException e) {
                LOG.warn("Failed to parse CMT3: {}", e.getMessage());
            }
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
        if (tags.containsKey(TiffIfdParser.TAG_ORIENTATION)) {
            if (tags.get(TiffIfdParser.TAG_ORIENTATION) instanceof TiffIfdParser.TagValue.IntValue iv) {
                int orient = iv.value();
                if (orient >= 1 && orient <= 8) {
                    metadata.setOrientation(orient);
                    LOG.debug("EXIF orientation: {}", orient);
                }
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
                    metadata.setShutterSpeed(rv.numeratorValue() + "/" + rv.denominatorValue());
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

        if (tags.isEmpty()) {
            return;
        }
        LOG.debug("Parsed {} GPS tags from CMT4", tags.size());

        // GPS Latitude: tag 0x0002 = degrees/minutes/seconds (3 RATIONALs or array)
        // GPS Latitude Ref: tag 0x0001 = "N" or "S"
        Double latitude = parseGpsCoordinate(tags,
                TiffIfdParser.TAG_GPS_LATITUDE, TiffIfdParser.TAG_GPS_LATITUDE_REF, "S");
        if (latitude != null) {
            metadata.setGpsLatitude(latitude);
        }

        // GPS Longitude: tag 0x0004, Ref: tag 0x0003 = "E" or "W"
        Double longitude = parseGpsCoordinate(tags,
                TiffIfdParser.TAG_GPS_LONGITUDE, TiffIfdParser.TAG_GPS_LONGITUDE_REF, "W");
        if (longitude != null) {
            metadata.setGpsLongitude(longitude);
        }

        // GPS Altitude: tag 0x0006 = RATIONAL, Ref: tag 0x0005 = 0(above) or 1(below)
        if (tags.containsKey(TiffIfdParser.TAG_GPS_ALTITUDE)) {
            TiffIfdParser.TagValue altVal = tags.get(TiffIfdParser.TAG_GPS_ALTITUDE);
            if (altVal instanceof TiffIfdParser.TagValue.RationalValue rv) {
                double alt = rv.toDouble();
                // Check altitude ref — 1 means below sea level
                if (tags.containsKey(TiffIfdParser.TAG_GPS_ALTITUDE_REF)) {
                    TiffIfdParser.TagValue refVal = tags.get(TiffIfdParser.TAG_GPS_ALTITUDE_REF);
                    if (refVal instanceof TiffIfdParser.TagValue.IntValue iv && iv.value() == 1) {
                        alt = -alt;
                    }
                }
                metadata.setGpsAltitude(alt);
            }
        }

        // GPS Timestamp: tag 0x0007 (3 RATIONALs for HH, MM, SS — per EXIF 2.3 spec)
        if (tags.containsKey(TiffIfdParser.TAG_GPS_TIMESTAMP)) {
            TiffIfdParser.TagValue tsVal = tags.get(TiffIfdParser.TAG_GPS_TIMESTAMP);
            if (tsVal instanceof TiffIfdParser.TagValue.RationalArrayValue rav && rav.count() >= 3) {
                double hours = rav.toDouble(0);
                double minutes = rav.toDouble(1);
                double seconds = rav.toDouble(2);
                // Format as HH:MM:SS (UTC per EXIF spec)
                int hh = (int) hours;
                int mm = (int) minutes;
                // Seconds may be fractional
                String ts = "%02d:%02d:%06.3f".formatted(hh, mm, seconds);
                metadata.setGpsTimestamp(ts);
                LOG.debug("GPS timestamp (UTC): {}", ts);
            } else if (tsVal instanceof TiffIfdParser.TagValue.RationalValue rv) {
                // Single rational — treat as fractional hours or seconds
                LOG.debug("GPS timestamp as single RATIONAL: {}", rv.toDouble());
            } else {
                LOG.debug("GPS timestamp tag present but unexpected type: {}",
                        tsVal.getClass().getSimpleName());
            }
        }
    }

    /**
     * Parses a GPS coordinate from EXIF GPS tags.
     *
     * <p>GPS coordinates in EXIF are stored as 3 RATIONAL values (degrees, minutes, seconds)
     * with a separate reference tag indicating hemisphere. For single-RATIONAL storage
     * (already in decimal degrees), we handle that too.</p>
     *
     * @param tags          the parsed GPS IFD tags
     * @param coordTag      tag ID for the coordinate (latitude or longitude)
     * @param refTag        tag ID for the reference (N/S or E/W)
     * @param negativeRef   the reference value that makes the coordinate negative ("S" or "W")
     * @return decimal degrees with sign, or null if not parseable
     */
    private static Double parseGpsCoordinate(Map<Integer, TiffIfdParser.TagValue> tags,
                                              int coordTag, int refTag, String negativeRef) {
        TiffIfdParser.TagValue coordVal = tags.get(coordTag);
        if (coordVal == null) {
            return null;
        }

        double degrees;
        if (coordVal instanceof TiffIfdParser.TagValue.RationalValue rv) {
            // Single RATIONAL — already in decimal degrees
            degrees = rv.toDouble();
        } else if (coordVal instanceof TiffIfdParser.TagValue.RationalArrayValue rav && rav.count() >= 3) {
            // 3 RATIONALs: degrees, minutes, seconds (standard EXIF GPS format)
            double deg = rav.toDouble(0);
            double min = rav.toDouble(1);
            double sec = rav.toDouble(2);
            degrees = deg + min / 60.0 + sec / 3600.0;
        } else {
            LOG.debug("GPS coordinate tag 0x{} has unsupported type: {}",
                    Integer.toHexString(coordTag), coordVal.getClass().getSimpleName());
            return null;
        }

        // Apply hemisphere sign
        if (tags.containsKey(refTag)) {
            TiffIfdParser.TagValue refVal = tags.get(refTag);
            if (refVal instanceof TiffIfdParser.TagValue.StringValue sv
                    && negativeRef.equalsIgnoreCase(sv.value().trim())) {
                degrees = -Math.abs(degrees);
            }
        }

        return degrees;
    }

    private static void parseCmt3(ImageInputStream input, IsoBox cmt3,
                                   Cr3Metadata metadata) throws IOException {
        CanonMakerNoteData makerNote = CanonMakerNoteParser.parse(
                input, cmt3.dataOffset(), cmt3.dataSize());

        metadata.setMakerNoteData(makerNote);

        if (makerNote.hasWhiteBalance()) {
            metadata.setWhiteBalanceMultipliers(makerNote.whiteBalanceMultipliers());
            LOG.debug("Set WB multipliers from MakerNote: R={}, G=1.0, B={}",
                    makerNote.whiteBalanceMultipliers()[0],
                    makerNote.whiteBalanceMultipliers()[2]);
        }
    }
}

