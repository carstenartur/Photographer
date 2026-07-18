package com.photographer.focusstack.cr3.bmff;

import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parsed CCTP (Canon CR3 Track Parameters) box containing CCDT entries.
 */
public record CctpBox(List<CcdtEntry> entries) {

    public static CctpBox parse(ImageInputStream input, IsoBox cctpBox) throws IOException {
        if (!"CCTP".equals(cctpBox.type())) {
            throw new IllegalArgumentException("Expected CCTP box, got: " + cctpBox.type());
        }
        List<CcdtEntry> entries = new ArrayList<>();
        for (IsoBox child : cctpBox.children()) {
            if ("CCDT".equals(child.type())) {
                entries.add(CcdtEntry.parse(input, child));
            }
        }
        return new CctpBox(Collections.unmodifiableList(entries));
    }
}
