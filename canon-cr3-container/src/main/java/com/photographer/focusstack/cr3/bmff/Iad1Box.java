package com.photographer.focusstack.cr3.bmff;

import com.photographer.focusstack.cr3.container.SensorCropGeometry;

import javax.imageio.stream.ImageInputStream;
import java.io.IOException;

/**
 * Parsed IAD1 (Image Area Dimensions 1) box.
 */
public record Iad1Box(
        int imageWidth,
        int imageHeight,
        boolean isBigImage,
        int cropLeft, int cropTop, int cropRight, int cropBottom,
        int leftObLeft, int leftObTop, int leftObRight, int leftObBottom,
        int topObLeft, int topObTop, int topObRight, int topObBottom,
        int activeLeft, int activeTop, int activeRight, int activeBottom
) {
    public static Iad1Box parse(ImageInputStream input, IsoBox box) throws IOException {
        if (!"IAD1".equals(box.type())) {
            throw new IllegalArgumentException("Expected IAD1 box, got: " + box.type());
        }
        input.seek(box.dataOffset());

        // Common header (16 bytes)
        input.readShort(); // +0: 0
        input.readShort(); // +2: 0
        int imageWidth = input.readUnsignedShort();   // +4
        int imageHeight = input.readUnsignedShort();  // +6
        input.readShort(); // +8: 1
        int slicedFlag = input.readUnsignedShort();   // +10
        input.readShort(); // +12: 1
        input.readShort(); // +14: 0

        boolean isBigImage = slicedFlag == 2;
        int cropLeft = 0, cropTop = 0, cropRight = 0, cropBottom = 0;
        int leftObLeft = 0, leftObTop = 0, leftObRight = 0, leftObBottom = 0;
        int topObLeft = 0, topObTop = 0, topObRight = 0, topObBottom = 0;
        int activeLeft = 0, activeTop = 0, activeRight = 0, activeBottom = 0;

        if (box.dataSize() >= 48) {
            cropLeft = input.readUnsignedShort();    // +16
            cropTop = input.readUnsignedShort();     // +18
            cropRight = input.readUnsignedShort();   // +20
            cropBottom = input.readUnsignedShort();  // +22
            leftObLeft = input.readUnsignedShort();  // +24
            leftObTop = input.readUnsignedShort();   // +26
            leftObRight = input.readUnsignedShort(); // +28
            leftObBottom = input.readUnsignedShort();// +30
            topObLeft = input.readUnsignedShort();   // +32
            topObTop = input.readUnsignedShort();    // +34
            topObRight = input.readUnsignedShort();  // +36
            topObBottom = input.readUnsignedShort(); // +38
            activeLeft = input.readUnsignedShort();  // +40
            activeTop = input.readUnsignedShort();   // +42
            activeRight = input.readUnsignedShort(); // +44
            activeBottom = input.readUnsignedShort();// +46
        } else if (box.dataSize() >= 32) {
            input.readShort(); // unknown1
            input.readShort(); // unknown2
            cropRight = input.readUnsignedShort();
            cropBottom = input.readUnsignedShort();
        }

        return new Iad1Box(imageWidth, imageHeight, isBigImage,
                cropLeft, cropTop, cropRight, cropBottom,
                leftObLeft, leftObTop, leftObRight, leftObBottom,
                topObLeft, topObTop, topObRight, topObBottom,
                activeLeft, activeTop, activeRight, activeBottom);
    }

    /**
     * Creates a SensorCropGeometry from this IAD1 data.
     *
     * <p>Active area values are clamped to valid sensor bounds because some Canon
     * firmware versions produce values that overflow the actual sensor dimensions
     * by a few pixels, especially in 1.6× crop mode.</p>
     */
    public SensorCropGeometry toSensorCropGeometry(int sensorW, int sensorH) {
        // Clamp active area to sensor bounds (firmware may produce overflow values)
        int clampedLeft = Math.max(0, Math.min(activeLeft(), sensorW));
        int clampedTop = Math.max(0, Math.min(activeTop(), sensorH));
        int clampedRight = Math.max(clampedLeft, Math.min(activeRight(), sensorW));
        int clampedBottom = Math.max(clampedTop, Math.min(activeBottom(), sensorH));
        return SensorCropGeometry.withActiveArea(sensorW, sensorH,
                clampedLeft, clampedTop, clampedRight, clampedBottom);
    }
}
