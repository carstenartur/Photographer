package com.photographer.focusstack.cr3.imageio;

import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.bmff.Iad1Box;
import com.photographer.focusstack.cr3.metadata.Cr3Metadata;
import com.photographer.focusstack.dng.imageio.DngImageMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.imageio.metadata.IIOMetadataNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link Cr3ImageMetadata}.
 */
class Cr3ImageMetadataTest {

    @Test
    @DisplayName("getAsTree returns non-null root with correct format name")
    void getAsTreeBasic() {
        Cr3Metadata meta = buildTestMetadata();
        Cr3ImageMetadata iioMeta = new Cr3ImageMetadata(meta);

        Node tree = iioMeta.getAsTree(Cr3ImageMetadata.NATIVE_FORMAT_NAME);
        assertThat(tree).isNotNull();
        assertThat(tree.getNodeName()).isEqualTo("CanonCR3Metadata");
    }

    @Test
    @DisplayName("getAsTree throws for unknown format name")
    void getAsTreeUnknownFormat() {
        Cr3ImageMetadata iioMeta = new Cr3ImageMetadata(new Cr3Metadata());
        assertThatThrownBy(() -> iioMeta.getAsTree("unknown_format"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Camera node contains make and model")
    void cameraNode() {
        Cr3Metadata meta = buildTestMetadata();
        Cr3ImageMetadata iioMeta = new Cr3ImageMetadata(meta);
        IIOMetadataNode tree = (IIOMetadataNode) iioMeta.getAsTree(Cr3ImageMetadata.NATIVE_FORMAT_NAME);

        IIOMetadataNode camera = findChild(tree, "Camera");
        assertThat(camera).isNotNull();
        assertThat(camera.getAttribute("make")).isEqualTo("Canon");
        assertThat(camera.getAttribute("model")).isEqualTo("Canon PowerShot G5 X Mark II");
    }

    @Test
    @DisplayName("Image node contains geometry and orientation")
    void imageNode() {
        Cr3Metadata meta = buildTestMetadata();
        meta.setOrientation(6);
        Cr3ImageMetadata iioMeta = new Cr3ImageMetadata(meta);
        IIOMetadataNode tree = (IIOMetadataNode) iioMeta.getAsTree(Cr3ImageMetadata.NATIVE_FORMAT_NAME);

        IIOMetadataNode image = findChild(tree, "Image");
        assertThat(image).isNotNull();
        assertThat(image.getAttribute("sensorWidth")).isEqualTo("5496");
        assertThat(image.getAttribute("sensorHeight")).isEqualTo("3672");
        assertThat(image.getAttribute("orientation")).isEqualTo("6");
        assertThat(image.getAttribute("bitDepth")).isEqualTo("14");
    }

    @Test
    @DisplayName("WhiteBalance node uses metadata WB when available")
    void whiteBalanceFromMetadata() {
        Cr3Metadata meta = buildTestMetadata();
        meta.setWhiteBalanceMultipliers(new double[]{2.1, 1.0, 1.7});
        Cr3ImageMetadata iioMeta = new Cr3ImageMetadata(meta);
        IIOMetadataNode tree = (IIOMetadataNode) iioMeta.getAsTree(Cr3ImageMetadata.NATIVE_FORMAT_NAME);

        IIOMetadataNode wb = findChild(tree, "WhiteBalance");
        assertThat(wb).isNotNull();
        assertThat(wb.getAttribute("r")).isEqualTo("2.1");
        assertThat(wb.getAttribute("g")).isEqualTo("1.0");
        assertThat(wb.getAttribute("b")).isEqualTo("1.7");
    }

    @Test
    @DisplayName("GPS node present when GPS data available")
    void gpsNodePresent() {
        Cr3Metadata meta = buildTestMetadata();
        meta.setGpsLatitude(48.8566);
        meta.setGpsLongitude(2.3522);
        meta.setGpsAltitude(35.0);
        Cr3ImageMetadata iioMeta = new Cr3ImageMetadata(meta);
        IIOMetadataNode tree = (IIOMetadataNode) iioMeta.getAsTree(Cr3ImageMetadata.NATIVE_FORMAT_NAME);

        IIOMetadataNode gps = findChild(tree, "GPS");
        assertThat(gps).isNotNull();
        assertThat(gps.getAttribute("latitude")).isEqualTo("48.8566");
        assertThat(gps.getAttribute("longitude")).isEqualTo("2.3522");
        assertThat(gps.getAttribute("altitude")).isEqualTo("35.0");
    }

    @Test
    @DisplayName("GPS node absent when no GPS data")
    void gpsNodeAbsent() {
        Cr3ImageMetadata iioMeta = new Cr3ImageMetadata(new Cr3Metadata());
        IIOMetadataNode tree = (IIOMetadataNode) iioMeta.getAsTree(Cr3ImageMetadata.NATIVE_FORMAT_NAME);

        IIOMetadataNode gps = findChild(tree, "GPS");
        assertThat(gps).isNull();
    }

    @Test
    @DisplayName("Capture node contains EXIF info")
    void captureNode() {
        Cr3Metadata meta = buildTestMetadata();
        Cr3ImageMetadata iioMeta = new Cr3ImageMetadata(meta);
        IIOMetadataNode tree = (IIOMetadataNode) iioMeta.getAsTree(Cr3ImageMetadata.NATIVE_FORMAT_NAME);

        IIOMetadataNode capture = findChild(tree, "Capture");
        assertThat(capture).isNotNull();
        assertThat(capture.getAttribute("iso")).isEqualTo("200");
        assertThat(capture.getAttribute("shutterSpeed")).isEqualTo("1/250");
        assertThat(capture.getAttribute("aperture")).isEqualTo("f/2.8");
    }

    @Test
    @DisplayName("isReadOnly returns true")
    void readOnly() {
        Cr3ImageMetadata iioMeta = new Cr3ImageMetadata(new Cr3Metadata());
        assertThat(iioMeta.isReadOnly()).isTrue();
    }

    @Test
    @DisplayName("mergeTree throws since read-only")
    void mergeTreeThrows() {
        Cr3ImageMetadata iioMeta = new Cr3ImageMetadata(new Cr3Metadata());
        assertThatThrownBy(() -> iioMeta.mergeTree(
                Cr3ImageMetadata.NATIVE_FORMAT_NAME, new IIOMetadataNode("root")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("getCr3Metadata returns underlying metadata")
    void getCr3Metadata() {
        Cr3Metadata meta = buildTestMetadata();
        Cr3ImageMetadata iioMeta = new Cr3ImageMetadata(meta);
        assertThat(iioMeta.getCr3Metadata()).isSameAs(meta);
    }

    @Test
    void dngPatternAndLevelsStartAtTheActivePixel() throws Exception {
        var cmp = new Cmp1Box((short) 256, 8, 8, 8, 8, 14, 4, 2, 0, 0, false, 0, false);
        var iad = new Iad1Box(8, 8, true, 1, 0, 6, 5, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 7, 7);
        var color = new Cr3SensorCalibration.ColorData(2000, 1000, 1000, 1500, 100, 200, 300, 400, 15000);
        var metadata = new Cr3ImageMetadata(buildTestMetadata(), new Cr3SensorCalibration(cmp, iad, color));
        var dng = DngImageMetadata.fromCr3(metadata).toDirectory();
        assertThat(dng.getTIFFField(33422).getAsBytes()).containsExactly(2, 1, 1, 0);
        assertThat(dng.getTIFFField(50714).getAsInts()).containsExactly(400, 300, 200, 100);
    }

    @Test
    void dngRequiresNativeCalibration() {
        var descriptive = new Cr3ImageMetadata(buildTestMetadata());
        assertThatThrownBy(() -> DngImageMetadata.fromCr3(descriptive))
                .isInstanceOf(javax.imageio.IIOException.class)
                .hasMessageContaining("ColorData");
    }

    private static Cr3Metadata buildTestMetadata() {
        Cr3Metadata meta = new Cr3Metadata();
        meta.setCameraMake("Canon");
        meta.setCameraModel("Canon PowerShot G5 X Mark II");
        meta.setSensorWidth(5496);
        meta.setSensorHeight(3672);
        meta.setIsoSpeed(200);
        meta.setShutterSpeed("1/250");
        meta.setAperture("f/2.8");
        meta.setFocalLength("50mm");
        meta.setBitDepth(14);
        return meta;
    }

    private static IIOMetadataNode findChild(IIOMetadataNode parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (name.equals(child.getNodeName())) {
                return (IIOMetadataNode) child;
            }
        }
        return null;
    }
}
