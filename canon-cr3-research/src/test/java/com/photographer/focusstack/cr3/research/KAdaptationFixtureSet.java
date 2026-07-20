package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.bmff.Iad1Box;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.container.Cr3Track;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class KAdaptationFixtureSet {
    static final List<String> PANEL = List.of(
            "eosr-iso-high.cr3",
            "eosr-iso-low.cr3",
            "eosr-iso-mid.cr3",
            "research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3",
            "research/g5xm2-dark-iso125-ss160-ff7p1-20260504-192532-02.cr3",
            "research/g5xm2-flat-iso1600-ss0p4s-ff1p6-20260504-193022-02.cr3",
            "sample-iso-high.cr3",
            "sample-tele.cr3",
            "sample-wb-tungsten.cr3",
            "sample-wide.cr3",
            "sample.cr3");

    static final Set<String> DISCRIMINATORS = Set.of(
            "sample-tele.cr3",
            "sample-wide.cr3",
            "research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3");

    record Structure(int width, int height, Cmp1Box cmp1, Iad1Box iad1) {}

    private KAdaptationFixtureSet() {}

    static List<Path> require(Path samples) {
        List<Path> fixtures = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String name : PANEL) {
            Path fixture = samples.resolve(name);
            if (!Files.isRegularFile(fixture)
                    || !Files.isRegularFile(referenceTiff(fixture))) {
                missing.add(name);
            } else {
                fixtures.add(fixture);
            }
        }
        assumeTrue(missing.isEmpty(),
                () -> "Missing fixed CR3 fixtures or references: " + missing);
        return List.copyOf(fixtures);
    }

    static Structure structure(Path fixture) throws Exception {
        try (ImageInputStream input = ImageIO.createImageInputStream(fixture.toFile())) {
            if (input == null) {
                throw new IOException("Cannot open " + fixture);
            }
            Cr3Container container = Cr3Parser.parse(input);
            Cr3Track raw = container.tracks().stream()
                    .filter(track -> track.type() == Cr3Track.TrackType.CRX_BIG
                            || track.type() == Cr3Track.TrackType.CRX_RAW)
                    .findFirst()
                    .orElseThrow(() -> new IOException("No RAW track in " + fixture));
            Cmp1Box cmp1 = raw.cmp1();
            if (cmp1 == null) {
                throw new IOException("No CMP1 in " + fixture);
            }
            return new Structure(cmp1.imageWidth(), cmp1.imageHeight(),
                    cmp1, raw.iad1());
        }
    }

    static Path referenceTiff(Path fixture) {
        return fixture.resolveSibling(fixture.getFileName().toString()
                .replaceAll("(?i)\\.cr3$", ".reference.tiff"));
    }

    static String name(Path samples, Path fixture) {
        return samples.relativize(fixture).toString().replace('\\', '/');
    }
}
