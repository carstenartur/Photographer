package com.photographer.focusstack.cr3.bmff;

import javax.imageio.stream.ImageInputStream;
import java.io.IOException;

/**
 * A single CCDT entry (Canon CR3 Track Descriptor).
 *
 * @param imageType     track image type (0=CRX_BIG, 1=CRX_SMALL, 2=JPEG_BIG, 4=CRX_DUAL_PIXEL)
 * @param dualPixelFlag 0 or 1
 * @param trakIndex     1-based track index
 */
public record CcdtEntry(long imageType, int dualPixelFlag, int trakIndex) {

    public static CcdtEntry parse(ImageInputStream input, IsoBox ccdtBox) throws IOException {
        if (!"CCDT".equals(ccdtBox.type())) {
            throw new IllegalArgumentException("Expected CCDT box, got: " + ccdtBox.type());
        }
        input.seek(ccdtBox.dataOffset());
        long imageType = input.readLong();
        int dualPixelFlag = input.readInt();
        int trakIndex = input.readInt();
        return new CcdtEntry(imageType, dualPixelFlag, trakIndex);
    }
}
