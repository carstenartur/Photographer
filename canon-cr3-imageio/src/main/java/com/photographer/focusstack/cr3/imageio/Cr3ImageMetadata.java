package com.photographer.focusstack.cr3.imageio;

import com.photographer.focusstack.cr3.metadata.Cr3Metadata;
import com.photographer.focusstack.cr3.processing.BlackLevelProcessor;
import com.photographer.focusstack.cr3.processing.WhiteBalanceProcessor;
import org.w3c.dom.Node;

import javax.imageio.metadata.IIOInvalidTreeException;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;

/**
 * {@link IIOMetadata} adapter exposing CR3 metadata via a native metadata tree.
 *
 * <p>This class adapts {@link Cr3Metadata} into the ImageIO metadata framework.
 * The native format name is {@value #NATIVE_FORMAT_NAME}.</p>
 *
 * <p>The metadata tree structure:</p>
 * <pre>{@code
 * <CanonCR3Metadata>
 *   <Camera make="Canon" model="Canon PowerShot G5 X Mark II"/>
 *   <Image sensorWidth="5496" sensorHeight="3672"
 *          visibleWidth="5472" visibleHeight="3648"
 *          orientation="1" bitDepth="14"
 *          bayerPattern="RGGB"
 *          blackLevel="512" whiteLevel="16383"/>
 *   <Crop left="..." top="..." right="..." bottom="..."/>
 *   <WhiteBalance r="1.9" g="1.0" b="1.5"/>
 *   <GPS latitude="..." longitude="..." altitude="..."/>
 *   <Capture iso="200" shutterSpeed="1/250" aperture="f/2.8"
 *           focalLength="50mm" dateTaken="..."/>
 * </CanonCR3Metadata>
 * }</pre>
 *
 * <p>This metadata is read-only. The primary metadata API remains
 * {@code Cr3Decoder.getMetadata()} for programmatic access.</p>
 */
public class Cr3ImageMetadata extends IIOMetadata {

    /** Native metadata format name for CR3 images. */
    public static final String NATIVE_FORMAT_NAME = "com_photographer_focusstack_cr3_1.0";

    private final Cr3Metadata metadata;
    private final Cr3SensorCalibration calibration;

    /**
     * Creates IIOMetadata wrapping the given CR3 metadata.
     *
     * @param metadata the CR3 metadata to expose (must not be null)
     */
    public Cr3ImageMetadata(Cr3Metadata metadata) {
        this(metadata, null);
    }

    Cr3ImageMetadata(Cr3Metadata metadata, Cr3SensorCalibration calibration) {
        super(
                false,                  // standardMetadataFormatSupported
                NATIVE_FORMAT_NAME,     // nativeMetadataFormatName
                null,                   // nativeMetadataFormatClassName (no IIOMetadataFormat impl)
                null,                   // extraMetadataFormatNames
                null                    // extraMetadataFormatClassNames
        );
        this.metadata = metadata;
        this.calibration = calibration;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Node getAsTree(String formatName) {
        if (!NATIVE_FORMAT_NAME.equals(formatName)) {
            throw new IllegalArgumentException("Unsupported format: " + formatName
                    + " (only '" + NATIVE_FORMAT_NAME + "' is supported)");
        }
        return buildTree();
    }

    @Override
    public void mergeTree(String formatName, Node root) throws IIOInvalidTreeException {
        throw new IllegalStateException("CR3 metadata is read-only");
    }

    @Override
    public void reset() {
        // Read-only — nothing to reset
    }

    /**
     * Returns the underlying CR3 metadata.
     *
     * @return the CR3 metadata (never null)
     */
    public Cr3Metadata getCr3Metadata() {
        return metadata;
    }

    /** Returns native RAW calibration, or null when only descriptive metadata is available. */
    public Cr3SensorCalibration sensorCalibration() {
        return calibration;
    }

    private IIOMetadataNode buildTree() {
        IIOMetadataNode root = new IIOMetadataNode("CanonCR3Metadata");

        // Camera info
        IIOMetadataNode camera = new IIOMetadataNode("Camera");
        camera.setAttribute("make", metadata.cameraMake());
        camera.setAttribute("model", metadata.cameraModel());
        root.appendChild(camera);

        // Image geometry
        IIOMetadataNode image = new IIOMetadataNode("Image");
        image.setAttribute("sensorWidth", String.valueOf(metadata.sensorWidth()));
        image.setAttribute("sensorHeight", String.valueOf(metadata.sensorHeight()));
        image.setAttribute("visibleWidth", String.valueOf(metadata.visibleWidth()));
        image.setAttribute("visibleHeight", String.valueOf(metadata.visibleHeight()));
        image.setAttribute("orientation", String.valueOf(metadata.orientation()));
        image.setAttribute("bitDepth", String.valueOf(metadata.bitDepth()));
        image.setAttribute("bayerPattern", calibration == null ? "unknown" : calibration.compression().cfaLayoutName());
        var color = calibration == null ? null : calibration.color();
        int whiteLevel = color == null ? (1 << metadata.bitDepth()) - 1 : color.white();
        image.setAttribute("whiteLevel", String.valueOf(whiteLevel));
        if (color != null) {
            image.setAttribute("blackLevel", "%d,%d,%d,%d".formatted(
                    color.redBlack(), color.greenRedBlack(), color.greenBlueBlack(), color.blueBlack()));
        } else if (metadata.perChannelBlackLevels() != null && metadata.perChannelBlackLevels().length >= 4) {
            int[] bl = metadata.perChannelBlackLevels();
            image.setAttribute("blackLevel", "%d,%d,%d,%d".formatted(bl[0], bl[1], bl[2], bl[3]));
        } else {
            image.setAttribute("blackLevel", String.valueOf(BlackLevelProcessor.DEFAULT_BLACK_LEVEL));
        }
        root.appendChild(image);

        // Crop — emit whenever active area differs from full sensor area
        boolean hasCrop = metadata.activeAreaLeft() > 0
                || metadata.activeAreaTop() > 0
                || (metadata.activeAreaRight() > 0 && metadata.activeAreaRight() < metadata.sensorWidth())
                || (metadata.activeAreaBottom() > 0 && metadata.activeAreaBottom() < metadata.sensorHeight());
        if (hasCrop) {
            IIOMetadataNode crop = new IIOMetadataNode("Crop");
            crop.setAttribute("left", String.valueOf(metadata.activeAreaLeft()));
            crop.setAttribute("top", String.valueOf(metadata.activeAreaTop()));
            crop.setAttribute("right", String.valueOf(metadata.activeAreaRight()));
            crop.setAttribute("bottom", String.valueOf(metadata.activeAreaBottom()));
            root.appendChild(crop);
        }

        // White balance
        double[] wb = metadata.whiteBalanceMultipliers();
        if (color != null && color.greenRedGain() + color.greenBlueGain() > 0) {
            double green = (color.greenRedGain() + color.greenBlueGain()) / 2.0;
            wb = new double[]{color.redGain() / green, 1.0, color.blueGain() / green};
        }
        if (wb == null) {
            wb = WhiteBalanceProcessor.DEFAULT_DAYLIGHT_MULTIPLIERS;
        }
        IIOMetadataNode wbNode = new IIOMetadataNode("WhiteBalance");
        wbNode.setAttribute("r", String.valueOf(wb[0]));
        wbNode.setAttribute("g", String.valueOf(wb[1]));
        wbNode.setAttribute("b", String.valueOf(wb[2]));
        root.appendChild(wbNode);

        // GPS
        if (metadata.gpsLatitude() != null && metadata.gpsLongitude() != null) {
            IIOMetadataNode gps = new IIOMetadataNode("GPS");
            gps.setAttribute("latitude", String.valueOf(metadata.gpsLatitude()));
            gps.setAttribute("longitude", String.valueOf(metadata.gpsLongitude()));
            if (metadata.gpsAltitude() != null) {
                gps.setAttribute("altitude", String.valueOf(metadata.gpsAltitude()));
            }
            root.appendChild(gps);
        }

        // Capture settings
        IIOMetadataNode capture = new IIOMetadataNode("Capture");
        capture.setAttribute("iso", String.valueOf(metadata.isoSpeed()));
        capture.setAttribute("shutterSpeed", metadata.shutterSpeed());
        capture.setAttribute("aperture", metadata.aperture());
        capture.setAttribute("focalLength", metadata.focalLength());
        capture.setAttribute("dateTaken", metadata.dateTaken());
        root.appendChild(capture);

        return root;
    }
}
