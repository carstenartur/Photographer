package com.photographer.focusstack.cr3.bmff;

import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parsed representation of an ISO BMFF 'ftyp' (file type) box.
 *
 * <p>The ftyp box is always the first box in a valid BMFF file and identifies the
 * file format. For Canon CR3 files, the major brand is "crx " and compatible brands
 * include "isom" and "crx ".</p>
 *
 * @param majorBrand       the 4-character major brand (e.g. "crx ")
 * @param minorVersion     the minor version number
 * @param compatibleBrands list of compatible brand codes
 */
public record FtypBox(
        String majorBrand,
        int minorVersion,
        List<String> compatibleBrands
) {

    /** CR3 major brand identifier. */
    public static final String CR3_BRAND = "crx ";

    /**
     * Parses an ftyp box from the given input stream.
     *
     * @param input the image input stream
     * @param box   the ftyp IsoBox (must have type "ftyp")
     * @return the parsed ftyp box
     * @throws IOException              if an I/O error occurs
     * @throws IllegalArgumentException if the box is not an ftyp box
     */
    public static FtypBox parse(ImageInputStream input, IsoBox box) throws IOException {
        if (!"ftyp".equals(box.type())) {
            throw new IllegalArgumentException("Expected ftyp box, got: " + box.type());
        }

        input.seek(box.dataOffset());

        byte[] brandBytes = new byte[4];
        input.readFully(brandBytes);
        String majorBrand = new String(brandBytes, StandardCharsets.US_ASCII);

        int minorVersion = input.readInt();

        long remaining = box.dataSize() - 8;
        List<String> brands = new ArrayList<>();
        while (remaining >= 4) {
            byte[] cb = new byte[4];
            input.readFully(cb);
            brands.add(new String(cb, StandardCharsets.US_ASCII));
            remaining -= 4;
        }

        return new FtypBox(majorBrand, minorVersion, Collections.unmodifiableList(brands));
    }

    /** Checks whether this ftyp identifies a Canon CR3 file. */
    public boolean isCr3() {
        return CR3_BRAND.equals(majorBrand) || compatibleBrands.contains(CR3_BRAND);
    }
}
