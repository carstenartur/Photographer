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
        return parse(input, box, 0);
    }

    /**
     * Reads CR3-relative table addresses as positions in the containing stream.
     * Entries with zero offset and zero size retain their zero offset.
     *
     * @param input stream containing the CR3 image
     * @param box CTBO box whose payload contains the table
     * @param origin absolute position of the first CR3 box in the stream, in bytes
     * @return decoded table with the entry addresses resolved in the input stream
     * @throws IOException if reading fails or an address cannot be represented
     */
    public static CtboBox parse(ImageInputStream input, IsoBox box, long origin) throws IOException {
        if (!"CTBO".equals(box.type())) {
            throw new IllegalArgumentException("Expected CTBO box, got: " + box.type());
        }
        long dataSize = box.dataSize();
        if (dataSize < 0) {
            throw new IllegalArgumentException(
                    "CTBO box has unknown size; cannot parse entries");
        }
        input.seek(box.dataOffset());
        int count = input.readInt();
        // Each entry: 4 (index) + 8 (offset) + 8 (size) = 20 bytes
        final int entrySize = 20;
        long available = dataSize - 4; // subtract the 4 bytes already read for count
        int maxEntries = (int) (available / entrySize);
        if (count < 0 || count > maxEntries) {
            throw new IllegalArgumentException(
                    "CTBO entry count %d exceeds available payload (%d bytes for %d max entries)"
                            .formatted(count, available, maxEntries));
        }
        List<CtboEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int index = input.readInt();
            long offset = input.readLong();
            long size = input.readLong();
            if (origin < 0 || offset < 0) {
                throw new IOException("CTBO offset exceeds the stream address range");
            }
            try {
                long address = offset == 0 && size == 0 ? 0 : Math.addExact(origin, offset);
                entries.add(new CtboEntry(index, address, size));
            } catch (ArithmeticException ex) {
                throw new IOException("CTBO offset exceeds the stream address range", ex);
            }
        }
        return new CtboBox(Collections.unmodifiableList(entries));
    }
}
