package com.photographer.focusstack.cr3.research;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Acceptance gate for both development and held-out C-RAW frames from four Canon camera models. */
@Tag("external-reference")
class CrawSensorIT {
    @Test
    @EnabledIfSystemProperty(named = "cr3.craw-sensor.mode", matches = "report|strict")
    void compareCompleteSensorFrames() throws Exception {
        String location = System.getProperty("cr3.craw-sensor.dir");
        assertNotNull(location, "Specify the cache prepared using craw-sensors.tsv");
        Path cache = Path.of(location);
        Path root = Path.of(System.getProperty("maven.multiModuleProjectDirectory", ".."));
        var registry = Files.readAllLines(root.resolve("scripts/research/public-crx/craw-sensors.tsv"));
        var results = new ArrayList<FullSensorDecodeIT.Result>();
        for (String line : registry.subList(1, registry.size())) {
            var record = LosslessOpeningHoldoutIT.Sample.fromRegistry(line);
            int[] size = switch (record.id()) {
                case "3506" -> new int[] {5632, 3710};
                case "4543" -> new int[] {6888, 4546};
                case "4656" -> new int[] {6288, 4056};
                case "4672" -> new int[] {6384, 4224};
                default -> throw new IllegalArgumentException("Unexpected C-RAW ID " + record.id());
            };
            results.add(FullSensorDecodeIT.inspect(new FullSensorDecodeIT.Fixture(record.id(), size[0], size[1],
                    3, record.inputHash(), record.pgmHash(), ".pgm"), cache));
        }
        var output = FullSensorDecodeIT.writeReport("craw-sensor", results);
        assertEquals(List.of("3506", "4543", "4656", "4672"),
                results.stream().map(result -> result.fixture().name()).toList());
        boolean strict = "strict".equals(System.getProperty("cr3.craw-sensor.mode"));
        for (var result : results) {
            if (strict) {
                assertEquals(FullSensorDecodeIT.Outcome.EXACT, result.outcome(), () -> result.row() + "\n" + output);
            } else {
                assertNotEquals(FullSensorDecodeIT.Outcome.DECODE_ERROR, result.outcome(), () -> result.row());
            }
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "cr3.craw-sensor.local-dir", matches = ".+")
    void compareFirstPartyApproximationBandRegression() throws Exception {
        Path cache = Path.of(System.getProperty("cr3.craw-sensor.local-dir"));
        Path samples = Path.of(System.getProperty("cr3.samples.dir", "src/test/resources/samples"), "research");
        String base = "g5xm2-detail-iso1600-ss0p4s-ff1p6-iq3-20260511-201012-";
        var fixtures = List.of(
                new FullSensorDecodeIT.Fixture(base + "02", 5632, 3710, 3,
                        "e3b30d62772103c8ef88008d084cc75036cb7dd6723b874e71bb782bab16034c",
                        "7324aaf607d259db1b7a482a0cd9e1e6034ed01f7b4556b49d88ffa01dfd6934", ".pgm"),
                new FullSensorDecodeIT.Fixture(base + "03", 5632, 3710, 3,
                        "35d0e985c1a42ed4aff43d54262a78128eb2e377fa09f5724b53a557e09f5125",
                        "140990b10dd6585deb40a7696ce30fa1aa72bec81c2d6e14edc21d5da0433f8d", ".pgm"));
        var results = new ArrayList<FullSensorDecodeIT.Result>();
        for (var fixture : fixtures) {
            results.add(FullSensorDecodeIT.inspect(fixture, samples.resolve(fixture.name() + ".cr3"),
                    cache.resolve(fixture.name() + ".pgm")));
        }
        var output = FullSensorDecodeIT.writeReport("craw-sensor-local", results);
        for (var result : results) {
            assertEquals(FullSensorDecodeIT.Outcome.EXACT, result.outcome(), () -> result.row() + "\n" + output);
        }
    }
}
