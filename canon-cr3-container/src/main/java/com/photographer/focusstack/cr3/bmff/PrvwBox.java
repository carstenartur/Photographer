package com.photographer.focusstack.cr3.bmff;

import javax.imageio.stream.ImageInputStream;
import java.io.IOException;

/**
 * Parsed PRVW (Preview) box found inside the top-level uuid box.
 */
public record PrvwBox(int width, int height, byte[] jpegData) {

    public static PrvwBox parse(ImageInputStream input, IsoBox box) throws IOException {
        if (!"PRVW".equals(box.type())) {
            throw new IllegalArgumentException("Expected PRVW box, got: " + box.type());
        }
        input.seek(box.dataOffset());
        input.readInt();            // unknown (0)
        input.readShort();          // unknown (1)
        int width = input.readUnsignedShort();
        int height = input.readUnsignedShort();
        input.readShort();          // unknown (1)
        int jpegSize = input.readInt();

        long currentPos = input.getStreamPosition();
        long available = box.offset() + box.size() - currentPos;
        int readSize = (int) Math.min(jpegSize, available);
        readSize = Math.max(0, readSize);
        byte[] jpegData = new byte[readSize];
        if (readSize > 0) {
            input.readFully(jpegData);
        }

        return new PrvwBox(width, height, jpegData);
    }
}
