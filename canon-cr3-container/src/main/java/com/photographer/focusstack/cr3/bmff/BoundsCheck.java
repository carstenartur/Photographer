package com.photographer.focusstack.cr3.bmff;

import com.photographer.focusstack.cr3.Cr3FormatException;

import javax.imageio.stream.ImageInputStream;
import java.io.IOException;

/**
 * Utility for hard bounds-checking of offsets and sizes against the stream/file end.
 *
 * <p>All CR3 and ISO-BMFF parsing must use these checks to prevent reads past the
 * end of the file or into invalid regions. This is a direct lesson from LibRaw 0.22,
 * which added explicit offset/filesize validation for CR3.</p>
 *
 * <p>All offsets and sizes are {@code long} (64-bit) throughout — never {@code int}.</p>
 */
public final class BoundsCheck {

    private BoundsCheck() {
        // utility class
    }

    /**
     * Validates that a seek target is within the stream bounds.
     *
     * @param input  the image input stream
     * @param offset the absolute byte offset to validate
     * @param context human-readable context for error messages (e.g. "moov box payload")
     * @throws Cr3FormatException if the offset is negative or beyond the stream length
     * @throws IOException if an I/O error occurs querying stream length
     */
    public static void validateOffset(ImageInputStream input, long offset, String context)
            throws Cr3FormatException, IOException {
        if (offset < 0) {
            throw new Cr3FormatException(
                    "Negative offset %d for %s".formatted(offset, context));
        }
        long streamLength = input.length();
        if (streamLength >= 0 && offset > streamLength) {
            throw new Cr3FormatException(
                    "Offset %d exceeds stream length %d for %s".formatted(offset, streamLength, context));
        }
    }

    /**
     * Validates that a range [offset, offset + size) is within the stream bounds.
     *
     * @param input   the image input stream
     * @param offset  the absolute start offset
     * @param size    the number of bytes in the range
     * @param context human-readable context for error messages
     * @throws Cr3FormatException if the range is invalid or exceeds stream bounds
     * @throws IOException if an I/O error occurs
     */
    public static void validateRange(ImageInputStream input, long offset, long size, String context)
            throws Cr3FormatException, IOException {
        if (offset < 0) {
            throw new Cr3FormatException(
                    "Negative offset %d for %s".formatted(offset, context));
        }
        if (size < 0) {
            throw new Cr3FormatException(
                    "Negative size %d for %s".formatted(size, context));
        }
        long end = offset + size;
        if (end < offset) {
            throw new Cr3FormatException(
                    "Offset + size overflow for %s (offset=%d, size=%d)".formatted(context, offset, size));
        }
        long streamLength = input.length();
        if (streamLength >= 0 && end > streamLength) {
            throw new Cr3FormatException(
                    "Range [%d, %d) exceeds stream length %d for %s"
                            .formatted(offset, end, streamLength, context));
        }
    }

    /**
     * Validates that a box's declared size is consistent with its offset and the stream.
     *
     * @param box     the ISO BMFF box to validate
     * @param input   the image input stream
     * @throws Cr3FormatException if the box extends beyond the stream
     * @throws IOException if an I/O error occurs
     */
    public static void validateBox(IsoBox box, ImageInputStream input)
            throws Cr3FormatException, IOException {
        if (box.size() > 0) {
            validateRange(input, box.offset(), box.size(),
                    "box '%s'".formatted(box.type()));
        }
    }

    /**
     * Validates that a box's declared size fits within a parent box boundary.
     *
     * @param child      the child box
     * @param parentEnd  the exclusive end offset of the parent box
     * @throws Cr3FormatException if the child exceeds the parent boundary
     */
    public static void validateChildBounds(IsoBox child, long parentEnd)
            throws Cr3FormatException {
        if (child.size() > 0) {
            long childEnd = child.offset() + child.size();
            if (childEnd > parentEnd) {
                throw new Cr3FormatException(
                        "Child box '%s' at offset %d (size %d) exceeds parent boundary %d"
                                .formatted(child.type(), child.offset(), child.size(), parentEnd));
            }
        }
    }
}
