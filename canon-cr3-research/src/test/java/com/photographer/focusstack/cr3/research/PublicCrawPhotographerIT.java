package com.photographer.focusstack.cr3.research;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Retains the RG2 two-camera C-RAW diagnostic and its original replay command. */
@Tag("external-reference")
@EnabledIfSystemProperty(named = "cr3.public.craw.enabled", matches = "true")
class PublicCrawPhotographerIT {
    @Test
    void compareFullSensorValues() throws Exception {
        var results = FullSensorDecodeIT.inspectCraw();
        var output = FullSensorDecodeIT.writeReport("public-craw-photographer", results);
        for (var result : results) {
            assertNotEquals(FullSensorDecodeIT.Outcome.DECODE_ERROR, result.outcome(), output.toString());
        }
    }
}
