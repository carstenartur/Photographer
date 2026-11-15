package com.photographer.focusstack.cr3.imagej;

import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.imageio.CanonCr3ImageReadParam;
import com.photographer.focusstack.cr3.imageio.CanonCr3ImageReaderSpi;
import com.photographer.focusstack.cr3.imageio.Cr3ImageMetadata;
import ij.IJ;
import ij.ImagePlus;
import ij.io.OpenDialog;
import ij.plugin.PlugIn;
import ij.process.ShortProcessor;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import javax.imageio.ImageReader;
import javax.imageio.stream.FileImageInputStream;

/** Imports the full Bayer mosaic at its recorded sensor-code levels. */
public final class Cr3SensorImporter implements PlugIn {
    @Override
    public void run(String arg) {
        String path = arg;
        if (path == null || path.isEmpty()) {
            path = new OpenDialog("Open CR3 Sensor Data", null).getPath();
            if (path == null) {
                return;
            }
        }
        try {
            open(Path.of(path)).show();
        } catch (IOException | IllegalArgumentException e) {
            IJ.error("CR3 Sensor Data", path + "\n" + e.getMessage());
        }
    }

    /**
     * Reads a CR3 into a single unsigned 16-bit ImageJ plane in sensor coordinates.
     * The caller owns the image and can use it without opening an ImageJ window.
     *
     * @param path Canon CR3 file
     * @return the full sensor mosaic with available camera calibration in image properties
     * @throws IOException if the file cannot be read or decoded
     */
    public static ImagePlus open(Path path) throws IOException {
        ImageReader reader = new CanonCr3ImageReaderSpi().createReaderInstance();
        try (var input = new FileImageInputStream(path.toFile())) {
            reader.setInput(input);
            var param = new CanonCr3ImageReadParam();
            param.setDecodeOptions(Cr3DecodeOptions.rawSensor());
            BufferedImage sensor = reader.read(0, param);
            // The full sensor read returns a packed TYPE_USHORT_GRAY image. The ImageJ
            // constructor adopts its short array directly, preserving every Bayer code.
            ImagePlus image = new ImagePlus(path.getFileName().toString(), new ShortProcessor(sensor));
            image.setIgnoreGlobalCalibration(true);
            if (reader.getImageMetadata(0) instanceof Cr3ImageMetadata metadata) {
                copySensorMetadata(metadata, image);
            }
            return image;
        } finally {
            reader.dispose();
        }
    }

    private static void copySensorMetadata(Cr3ImageMetadata metadata, ImagePlus image) {
        image.setProp("CR3.CameraModel", metadata.getCr3Metadata().cameraModel());
        var sensor = metadata.sensorCalibration();
        if (sensor == null) {
            return;
        }
        var compression = sensor.compression();
        image.setProp("CR3.BitDepth", compression.bitsPerSample());
        image.setProp("CR3.CFA", compression.cfaLayoutName());
        var area = sensor.geometry();
        if (area != null && area.isBigImage() && area.activeLeft() < area.activeRight()
                && area.activeTop() < area.activeBottom() && area.activeRight() < image.getWidth()
                && area.activeBottom() < image.getHeight()) {
            image.setProp("CR3.ActiveAreaLTRBInclusive", "%d,%d,%d,%d".formatted(
                    area.activeLeft(), area.activeTop(), area.activeRight(), area.activeBottom()));
        }
        var color = sensor.color();
        if (color != null) {
            image.setProp("CR3.BlackLevelRGGB", "%d,%d,%d,%d".formatted(
                    color.redBlack(), color.greenRedBlack(), color.greenBlueBlack(), color.blueBlack()));
            image.setProp("CR3.WhiteBalanceGainRGGB", "%d,%d,%d,%d".formatted(
                    color.redGain(), color.greenRedGain(), color.greenBlueGain(), color.blueGain()));
            image.setProp("CR3.WhiteLevel", color.white());
        }
    }
}
