package com.photographer.focusstack.cr3.processing;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class LensProfilesTest {
    private static final String ZOOM = "RF24-105mm F4 L IS USM";
    private static final String MACRO = "RF85mm F2 MACRO IS STM";
    private static final String ZOOM_TABLE = "canon-rf24-105-f4l.tsv";

    @Test
    void usesFixedCompactLensAndRetainsExactPublishedKnots() throws IOException {
        var result = LensProfiles.find("Canon PowerShot G5 X Mark II", "", 8.8, 2.8, 10, 5496, 3668)
                .orElseThrow();
        String table = "canon-g5x-mark-ii.tsv";
        double[] d = row(table, "ptlens\t8.8\t");
        double[] tca = row(table, "poly3\t8.8\t");
        double[] v = row(table, "pa\t8.8\t2.8\t10\t");
        assertEquals(new OpticalCorrection(d[0], d[1], d[2], tca[0], tca[1], v[0], v[1], v[2]), result);
        for (double distance : new double[] { 0, 0.41, 25, Double.POSITIVE_INFINITY }) {
            assertEquals(result, LensProfiles.find("PowerShot G5 X Mark II", null, 8.8, 2.8, distance,
                    5496, 3668).orElseThrow());
        }
    }

    @Test
    void interpolatesAtCaptureFocusAndKeepsExactModelMatch() throws IOException {
        double[] d = row(ZOOM_TABLE, "ptlens\t24.0\t");
        double[] tca = row(ZOOM_TABLE, "poly3\t24.0\t");
        double[] near = row(ZOOM_TABLE, "pa\t24\t4\t0.43\t");
        double[] far = row(ZOOM_TABLE, "pa\t24\t4\t0.94\t");
        // .44 m is the reported midpoint of the EOS R fixture's focus distance interval.
        double fraction = (1 / .44 - 1 / .43) / (1 / .94 - 1 / .43);
        var actual = LensProfiles.find("Canon EOS R", ZOOM, 24, 4, .44, 6744, 4500).orElseThrow();
        assertEquals(d[0], actual.a());
        assertEquals(d[1], actual.b());
        assertEquals(d[2], actual.c());
        assertEquals(tca[0], actual.redScale());
        assertEquals(tca[1], actual.blueScale());
        assertEquals(near[0] + fraction * (far[0] - near[0]), actual.k1(), 1e-12);
        assertEquals(near[1] + fraction * (far[1] - near[1]), actual.k2(), 1e-12);
        assertEquals(near[2] + fraction * (far[2] - near[2]), actual.k3(), 1e-12);
        assertEquals(actual, LensProfiles.find("EOS R", "Canon RF 24-105mm f/4L IS USM", 24, 4, .44,
                6744, 4500).orElseThrow());
    }

    @Test
    void blendsFocalSamplesAndStopsForFixedAndZoomLenses() throws IOException {
        double[] left = row(ZOOM_TABLE, "ptlens\t24.0\t");
        double[] right = row(ZOOM_TABLE, "ptlens\t35.0\t");
        var zoom = LensProfiles.find("EOS R", ZOOM, 29.5, 4, .43, 6744, 4500).orElseThrow();
        assertEquals((left[0] + right[0]) / 2, zoom.a(), 1e-12);
        double[] open = row("canon-rf85-f2-macro.tsv", "pa\t85.0\t5.6\t0.7\t");
        double[] stopped = row("canon-rf85-f2-macro.tsv", "pa\t85.0\t29.0\t0.7\t");
        var macro = LensProfiles.find("EOS R", MACRO, 85, Math.sqrt(5.6 * 29), .7, 6744, 4500)
                .orElseThrow();
        assertEquals((open[1] + stopped[1]) / 2, macro.k2(), 1e-12);
    }

    @Test
    void omitsShadingWhenItsInputsAreUnknownAndRefusesUnverifiedCoverage() {
        var known = LensProfiles.find("EOS R", ZOOM, 24, 4, .43, 6744, 4500).orElseThrow();
        var geometryOnly = new OpticalCorrection(known.a(), known.b(), known.c(), known.redScale(),
                known.blueScale(), 0, 0, 0);
        assertEquals(geometryOnly, LensProfiles.find("EOS R", ZOOM, 24, 0, .43, 6744, 4500).orElseThrow());
        for (double unknown : new double[] { 0, -1, Double.NaN }) {
            assertEquals(geometryOnly, LensProfiles.find("EOS R", ZOOM, 24, 4, unknown, 6744, 4500)
                    .orElseThrow());
        }
        var far = LensProfiles.find("EOS R", ZOOM, 24, 4, 1000, 6744, 4500).orElseThrow();
        assertNotEquals(geometryOnly, far);
        assertEquals(far, LensProfiles.find("EOS R", ZOOM, 24, 4, Double.POSITIVE_INFINITY, 6744, 4500)
                .orElseThrow());
        for (String lens : new String[] { null, "", "EF24-105mm F4L IS USM", "RF24-105mm F4-7.1 IS STM" }) {
            assertTrue(LensProfiles.find("EOS R", lens, 24, 4, .43, 6744, 4500).isEmpty());
        }
        assertTrue(LensProfiles.find("EOS R", MACRO, 50, 4, .43, 6744, 4500).isEmpty());
        assertTrue(LensProfiles.find("EOS R", ZOOM, 24, 4, .43, 4176, 2784).isEmpty());
        assertTrue(LensProfiles.find("PowerShot G5 X", null, 8.8, 4, 0, 5496, 3668).isEmpty());
        assertTrue(LensProfiles.find("EOS R5", ZOOM, 24, 4, .43, 6744, 4500).isEmpty());
    }

    private static double[] row(String table, String prefix) throws IOException {
        var stream = Objects.requireNonNull(LensProfiles.class.getResourceAsStream("/META-INF/lensfun/" + table));
        try (var in = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String text = in.lines().filter(line -> line.startsWith(prefix)).findFirst().orElseThrow();
            return java.util.Arrays.stream(text.substring(prefix.length()).split("\t"))
                    .mapToDouble(Double::parseDouble).toArray();
        }
    }
}
