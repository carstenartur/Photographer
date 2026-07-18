package com.photographer.focusstack.cr3.metadata;

import com.photographer.focusstack.cr3.bmff.IsoBox;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Track;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.util.Map;

/** Reads EXIF, GPS and Canon MakerNote metadata independently of RAW decoding. */
public final class Cr3MetadataReader {

    private static final Logger LOG = LoggerFactory.getLogger(Cr3MetadataReader.class);

    private Cr3MetadataReader() {}

    public static Cr3Metadata read(ImageInputStream input, Cr3Container container)
            throws IOException {
        Cr3Metadata metadata = new Cr3Metadata();
        metadata.setCameraMake("Canon");

        for (Cr3Track track : container.tracks()) {
            if (track.type() == Cr3Track.TrackType.CRX_RAW
                    || track.type() == Cr3Track.TrackType.CRX_BIG) {
                metadata.setSensorWidth(track.width());
                metadata.setSensorHeight(track.height());
                if (track.cmp1() != null) {
                    metadata.setBitDepth(track.cmp1().bitsPerSample());
                }
                if (track.iad1() != null) {
                    int sensorWidth = track.width();
                    int sensorHeight = track.height();
                    metadata.setActiveAreaLeft(Math.max(0,
                            Math.min(track.iad1().activeLeft(), sensorWidth)));
                    metadata.setActiveAreaTop(Math.max(0,
                            Math.min(track.iad1().activeTop(), sensorHeight)));
                    metadata.setActiveAreaRight(Math.max(0,
                            Math.min(track.iad1().activeRight(), sensorWidth)));
                    metadata.setActiveAreaBottom(Math.max(0,
                            Math.min(track.iad1().activeBottom(), sensorHeight)));
                }
                break;
            }
        }

        if (container.cncvBox() != null) {
            metadata.setCompressorVersion(container.cncvBox().versionString());
        }

        if (container.moovBox() != null) {
            readCmtBoxes(input, container.moovBox(), metadata);
        }

        LOG.debug("Extracted metadata: {}", metadata);
        return metadata;
    }

    private static void readCmtBoxes(ImageInputStream input, IsoBox moovBox,
                                     Cr3Metadata metadata) {
        parseIfPresent(input, moovBox, "CMT1", "EXIF IFD0",
                box -> parseCmt1(input, box, metadata));
        parseIfPresent(input, moovBox, "CMT2", "EXIF ExifIFD",
                box -> parseCmt2(input, box, metadata));
        parseIfPresent(input, moovBox, "CMT3", "Canon MakerNote",
                box -> parseCmt3(input, box, metadata));
        parseIfPresent(input, moovBox, "CMT4", "GPS IFD",
                box -> parseCmt4(input, box, metadata));
    }

    private static void parseIfPresent(ImageInputStream input, IsoBox moovBox,
                                       String boxType, String description,
                                       IoBoxConsumer parser) {
        IsoBox box = moovBox.findDescendant(boxType);
        if (box == null) {
            return;
        }
        LOG.debug("Found {} ({}) at offset {}, size {}",
                boxType, description, box.offset(), box.size());
        try {
            parser.accept(box);
        } catch (IOException e) {
            LOG.warn("Failed to parse {}: {}", boxType, e.getMessage());
        }
    }

    private static void parseCmt1(ImageInputStream input, IsoBox cmt1,
                                  Cr3Metadata metadata) throws IOException {
        Map<Integer, TiffIfdParser.TagValue> tags =
                TiffIfdParser.parseIfd(input, cmt1.dataOffset(), cmt1.dataSize());

        if (tags.get(TiffIfdParser.TAG_MAKE) instanceof TiffIfdParser.TagValue.StringValue sv) {
            metadata.setCameraMake(sv.value());
        }
        if (tags.get(TiffIfdParser.TAG_MODEL) instanceof TiffIfdParser.TagValue.StringValue sv) {
            metadata.setCameraModel(sv.value());
        }
        if (tags.get(TiffIfdParser.TAG_DATETIME) instanceof TiffIfdParser.TagValue.StringValue sv) {
            metadata.setDateTaken(sv.value());
        }
        if (tags.get(TiffIfdParser.TAG_ORIENTATION) instanceof TiffIfdParser.TagValue.IntValue iv) {
            int orientation = iv.value();
            if (orientation >= 1 && orientation <= 8) {
                metadata.setOrientation(orientation);
                LOG.debug("EXIF orientation: {}", orientation);
            }
        }
    }

    private static void parseCmt2(ImageInputStream input, IsoBox cmt2,
                                  Cr3Metadata metadata) throws IOException {
        Map<Integer, TiffIfdParser.TagValue> tags =
                TiffIfdParser.parseIfd(input, cmt2.dataOffset(), cmt2.dataSize());

        if (tags.get(TiffIfdParser.TAG_ISO_SPEED) instanceof TiffIfdParser.TagValue.IntValue iv) {
            metadata.setIsoSpeed(iv.value());
        }
        if (tags.get(TiffIfdParser.TAG_EXPOSURE_TIME)
                instanceof TiffIfdParser.TagValue.RationalValue rv) {
            metadata.setExposureTime(rv.toDouble());
            if (rv.denominator() != 0) {
                metadata.setShutterSpeed(rv.numerator() + "/" + rv.denominator());
            }
        }
        if (tags.get(TiffIfdParser.TAG_FNUMBER)
                instanceof TiffIfdParser.TagValue.RationalValue rv) {
            metadata.setFNumber(rv.toDouble());
            metadata.setAperture("f/%.1f".formatted(rv.toDouble()));
        }
        if (tags.get(TiffIfdParser.TAG_FOCAL_LENGTH)
                instanceof TiffIfdParser.TagValue.RationalValue rv) {
            double millimetres = rv.toDouble();
            metadata.setFocalLengthMm(millimetres);
            metadata.setFocalLength("%.0fmm".formatted(millimetres));
        }
        if (tags.get(TiffIfdParser.TAG_DATE_TIME_ORIGINAL)
                instanceof TiffIfdParser.TagValue.StringValue sv) {
            metadata.setCaptureDateTime(sv.value());
            if (metadata.dateTaken().isEmpty()) {
                metadata.setDateTaken(sv.value());
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

        Double latitude = parseGpsCoordinate(tags,
                TiffIfdParser.TAG_GPS_LATITUDE,
                TiffIfdParser.TAG_GPS_LATITUDE_REF,
                "S");
        if (latitude != null) {
            metadata.setGpsLatitude(latitude);
        }

        Double longitude = parseGpsCoordinate(tags,
                TiffIfdParser.TAG_GPS_LONGITUDE,
                TiffIfdParser.TAG_GPS_LONGITUDE_REF,
                "W");
        if (longitude != null) {
            metadata.setGpsLongitude(longitude);
        }

        TiffIfdParser.TagValue altitudeValue = tags.get(TiffIfdParser.TAG_GPS_ALTITUDE);
        if (altitudeValue instanceof TiffIfdParser.TagValue.RationalValue rv) {
            double altitude = rv.toDouble();
            if (tags.get(TiffIfdParser.TAG_GPS_ALTITUDE_REF)
                    instanceof TiffIfdParser.TagValue.IntValue iv && iv.value() == 1) {
                altitude = -altitude;
            }
            metadata.setGpsAltitude(altitude);
        }

        TiffIfdParser.TagValue timestampValue = tags.get(TiffIfdParser.TAG_GPS_TIMESTAMP);
        if (timestampValue instanceof TiffIfdParser.TagValue.RationalArrayValue rav
                && rav.count() >= 3) {
            int hours = (int) rav.toDouble(0);
            int minutes = (int) rav.toDouble(1);
            double seconds = rav.toDouble(2);
            String timestamp = "%02d:%02d:%06.3f".formatted(hours, minutes, seconds);
            metadata.setGpsTimestamp(timestamp);
            LOG.debug("GPS timestamp (UTC): {}", timestamp);
        } else if (timestampValue instanceof TiffIfdParser.TagValue.RationalValue rv) {
            LOG.debug("GPS timestamp as single RATIONAL: {}", rv.toDouble());
        } else if (timestampValue != null) {
            LOG.debug("GPS timestamp tag present but unexpected type: {}",
                    timestampValue.getClass().getSimpleName());
        }
    }

    private static Double parseGpsCoordinate(Map<Integer, TiffIfdParser.TagValue> tags,
                                             int coordinateTag, int referenceTag,
                                             String negativeReference) {
        TiffIfdParser.TagValue coordinateValue = tags.get(coordinateTag);
        if (coordinateValue == null) {
            return null;
        }

        double degrees;
        if (coordinateValue instanceof TiffIfdParser.TagValue.RationalValue rv) {
            degrees = rv.toDouble();
        } else if (coordinateValue instanceof TiffIfdParser.TagValue.RationalArrayValue rav
                && rav.count() >= 3) {
            degrees = rav.toDouble(0) + rav.toDouble(1) / 60.0 + rav.toDouble(2) / 3600.0;
        } else {
            LOG.debug("GPS coordinate tag 0x{} has unsupported type: {}",
                    Integer.toHexString(coordinateTag),
                    coordinateValue.getClass().getSimpleName());
            return null;
        }

        if (tags.get(referenceTag) instanceof TiffIfdParser.TagValue.StringValue sv
                && negativeReference.equalsIgnoreCase(sv.value().trim())) {
            degrees = -Math.abs(degrees);
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

    @FunctionalInterface
    private interface IoBoxConsumer {
        void accept(IsoBox box) throws IOException;
    }
}
