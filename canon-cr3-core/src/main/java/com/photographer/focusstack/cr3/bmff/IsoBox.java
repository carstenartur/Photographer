package com.photographer.focusstack.cr3.bmff;

import java.util.List;

/**
 * Represents a single ISO Base Media File Format (ISO 14496-12) box.
 *
 * <p>A box is the fundamental building block of BMFF-based file formats including
 * CR3, HEIF, and MP4. Each box has a 4-character type code, a size, and an offset
 * within the file. Container boxes (e.g. 'moov', 'trak') hold nested child boxes.</p>
 *
 * @param type     four-character box type code (e.g. "ftyp", "moov")
 * @param size     total size of the box in bytes (including header)
 * @param offset   absolute byte offset of the box start within the file
 * @param children nested child boxes (empty list for leaf boxes)
 */
public record IsoBox(
        String type,
        long size,
        long offset,
        List<IsoBox> children
) {
    /** Creates a leaf box with no children. */
    public IsoBox(String type, long size, long offset) {
        this(type, size, offset, List.of());
    }

    /** Returns the offset of the box payload (after the 8- or 16-byte header). */
    public long dataOffset() {
        return offset + headerSize();
    }

    /** Returns the payload size (total size minus header), or -1 if size is unknown. */
    public long dataSize() {
        if (size <= 0) {
            return -1;
        }
        return size - headerSize();
    }

    /**
     * Returns the header size of this box.
     * Standard boxes have an 8-byte header; boxes with size > 2^32 - 1 would use
     * an extended 16-byte header, but after parsing the actual size is stored in
     * {@link #size}, so the header is always 8 bytes for parsed boxes.
     */
    private long headerSize() {
        return 8;
    }

    /**
     * Finds the first child box with the given type.
     *
     * @param childType the 4-character type to search for
     * @return the first matching child, or {@code null} if not found
     */
    public IsoBox findChild(String childType) {
        for (IsoBox child : children) {
            if (child.type().equals(childType)) {
                return child;
            }
        }
        return null;
    }

    /**
     * Recursively finds the first descendant box with the given type (depth-first).
     *
     * @param descendantType the 4-character type to search for
     * @return the first matching descendant, or {@code null} if not found
     */
    public IsoBox findDescendant(String descendantType) {
        for (IsoBox child : children) {
            if (child.type().equals(descendantType)) {
                return child;
            }
            IsoBox found = child.findDescendant(descendantType);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "IsoBox[type='%s', size=%d, offset=%d, children=%d]"
                .formatted(type, size, offset, children.size());
    }
}
