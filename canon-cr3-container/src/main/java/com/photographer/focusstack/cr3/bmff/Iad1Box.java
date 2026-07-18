package com.photographer.focusstack.cr3.bmff;

import com.photographer.focusstack.cr3.container.SensorCropGeometry;

import javax.imageio.stream.ImageInputStream;
import java.io.IOException;

/** Parsed IAD1 (Image Area Dimensions 1) box. */
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

        input.readShort();
        input.readShort();
        int imageWidth = input.readUnsignedShort();
        int imageHeight = input.readUnsignedShort();
        input.readShort();
        int slicedFlag = input.readUnsignedShort();
        input.readShort();
        input.readShort();

        boolean isBigImage = slicedFlag == 2;
        int cropLeft = 0, cropTop = 0, cropRight = 0, cropBottom = 0;
        int leftObLeft = 0, leftObTop = 0, leftObRight = 0, leftObBottom = 0;
        int topObLeft = 0, topObTop = 0, topObRight = 0, topObBottom = 0;
        int activeLeft = 0, activeTop = 0, activeRight = 0, activeBottom = 0;

        if (box.dataSize() >= 48) {
            cropLeft = input.readUnsignedShort();
            cropTop = input.readUnsignedShort();
            cropRight = input.readUnsignedShort();
            cropBottom = input.readUnsignedShort();
            leftObLeft = input.readUnsignedShort();
            leftObTop = input.readUnsignedShort();
            leftObRight = input.readUnsignedShort();
            leftObBottom = input.readUnsignedShort();
            topObLeft = input.readUnsignedShort();
            topObTop = input.readUnsignedShort();
            topObRight = input.readUnsignedShort();
            topObBottom = input.readUnsignedShort();
            activeLeft = input.readUnsignedShort();
            activeTop = input.readUnsignedShort();
            activeRight = input.readUnsignedShort();
            activeBottom = input.readUnsignedShort();
        } else if (box.dataSize() >= 32) {
            input.readShort();
            input.readShort();
            cropRight = input.readUnsignedShort();
            cropBottom = input.readUnsignedShort();
        }

        return new Iad1Box(imageWidth, imageHeight, isBigImage,
                cropLeft, cropTop, cropRight, cropBottom,
                leftObLeft, leftObTop, leftObRight, leftObBottom,
                topObLeft, topObTop, topObRight, topObBottom,
                activeLeft, activeTop, activeRight, activeBottom);
    }

    /** Creates a sensor crop geometry with active coordinates clamped to bounds. */
    public SensorCropGeometry toSensorCropGeometry(int sensorW, int sensorH) {
        int clampedLeft = Math.max(0, Math.min(activeLeft(), sensorW));
        int clampedTop = Math.max(0, Math.min(activeTop(), sensorH));
        int clampedRight = Math.max(clampedLeft, Math.min(activeRight(), sensorW));
        int clampedBottom = Math.max(clampedTop, Math.min(activeBottom(), sensorH));
        return SensorCropGeometry.withActiveArea(sensorW, sensorH,
                clampedLeft, clampedTop, clampedRight, clampedBottom);
    }
}
