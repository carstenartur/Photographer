package com.photographer.focusstack.cr3.bmff;

import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parsed CTBO (Canon Track Byte Offsets) box.
 */
public record CtboBox(List<CtboEntry> entries) {

    public static CtboBox parse(ImageInputStream input, IsoBox box) throws IOException {
        if (!"CTBO".equals(box.type())) {
            throw new IllegalArgumentException("Expected CTBO box, got: " + box.type());
        }
        input.seek(box.dataOffset());
        int count = input.readInt();
        List<CtboEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int index = input.readInt();
            long offset = input.readLong();
            long size = input.readLong();
            entries.add(new CtboEntry(index, offset, size));
        }
        return new CtboBox(Collections.unmodifiableList(entries));
    }
}
