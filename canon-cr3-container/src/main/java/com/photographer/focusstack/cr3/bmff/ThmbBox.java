package com.photographer.focusstack.cr3.bmff;

import javax.imageio.stream.ImageInputStream;
import java.io.IOException;

/** Parsed THMB (Thumbnail) box found inside moov/uuid. */
public record ThmbBox(int version, int width, int height, byte[] jpegData) {

    public static ThmbBox parse(ImageInputStream input, IsoBox box) throws IOException {
        if (!"THMB".equals(box.type())) {
            throw new IllegalArgumentException("Expected THMB box, got: " + box.type());
        }
        input.seek(box.dataOffset());
        int version = input.readByte() & 0xFF;
        input.skipBytes(3);
        int width = input.readUnsignedShort();
        int height = input.readUnsignedShort();
        int jpegSize = input.readInt();
        if (version == 0) {
            input.skipBytes(4);
        }
        long currentPos = input.getStreamPosition();
        long available = box.offset() + box.size() - currentPos;
        int readSize = (int) Math.min(jpegSize, available);
        readSize = Math.max(0, readSize);
        byte[] jpegData = new byte[readSize];
        if (readSize > 0) {
            input.readFully(jpegData);
        }
        return new ThmbBox(version, width, height, jpegData);
    }
}
