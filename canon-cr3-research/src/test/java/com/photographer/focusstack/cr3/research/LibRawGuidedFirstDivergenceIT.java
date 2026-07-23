package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.raw.codec.SubbandExtractor;
import com.photographer.focusstack.cr3.reference.ReferenceImage;
import com.photographer.focusstack.cr3.reference.ReferenceImageLoader;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in, reference-backed research integration test for issue #276.
 *
 * <p>The test consumes only hash-locked RAW-linear TIFF sidecars produced by
 * LibRaw/rawpy as a black box. It does not inspect or depend on third-party
 * decoder source and it never changes production decoder semantics.</p>
 */
@Tag("external-reference")
@EnabledIfSystemProperty(named = "cr3.libraw.first-divergence.enabled", matches = "true")
class LibRawGuidedFirstDivergenceIT {

    private static final Path SAMPLES = Path.of(System.getProperty(
            "cr3.samples.dir", "src/test/resources/samples"));
    private static final Path OUTPUT = Path.of(System.getProperty(
            "cr3.research.output.dir", "target"))
            .resolve("libraw-guided-first-divergence");
    private static final Path MANIFEST = Path.of(System.getProperty(
            "cr3.reference.manifest.doc", "../docs/cr3-reference-manifest.md"));
    private static final String TSV_START = "<!-- reference-manifest-tsv:start -->";
    private static final String TSV_END = "<!-- reference-manifest-tsv:end -->";

    @Test
    void locateFirstReferenceDivergenceForManifestBoundLosslessPanel() throws Exception {
        List<ManifestEntry> entries = parseRawLinearManifestEntries();
        assertThat(entries).as("manifest-bound LibRaw TIFF references").isNotEmpty();

        Files.createDirectories(OUTPUT);
        List<ReferenceGuidedFirstDivergenceReport.FixtureResult> fixtures = new ArrayList<>();
        for (ManifestEntry entry : entries) {
            fixtures.add(analyzeFixture(entry));
        }

        String markdown = ReferenceGuidedFirstDivergenceReport.markdown(fixtures);
        String tsv = ReferenceGuidedFirstDivergenceReport.tsv(fixtures);
        String json = ReferenceGuidedFirstDivergenceReport.json(fixtures);
        Files.writeString(OUTPUT.resolve("results.md"), markdown, StandardCharsets.UTF_8);
        Files.writeString(OUTPUT.resolve("results.tsv"), tsv, StandardCharsets.UTF_8);
        Files.writeString(OUTPUT.resolve("results.json"), json, StandardCharsets.UTF_8);

        assertThat(fixtures).hasSize(entries.size());
        assertThat(fixtures).allSatisfy(fixture -> {
            assertThat(fixture.streams()).isNotEmpty();
            assertThat(fixture.streams()).allSatisfy(stream ->
                    assertThat(stream.classification()).isNotNull());
            long configured = fixture.streams().stream()
                    .filter(stream -> stream.initializationMode()
                            == ReferenceGuidedFirstDivergenceAnalyzer.InitializationMode
                            .CONFIGURED_BASELINE)
                    .count();
            long oracle = fixture.streams().stream()
                    .filter(stream -> stream.initializationMode()
                            == ReferenceGuidedFirstDivergenceAnalyzer.InitializationMode
                            .REFERENCE_FIRST_SAMPLE_ORACLE)
                    .count();
            assertThat(configured).isPositive().isEqualTo(oracle);
        });
        assertThat(OUTPUT.resolve("results.md")).isRegularFile();
        assertThat(OUTPUT.resolve("results.tsv")).isRegularFile();
        assertThat(OUTPUT.resolve("results.json")).isRegularFile();
        System.out.println(markdown);
    }

    private static ReferenceGuidedFirstDivergenceReport.FixtureResult analyzeFixture(
            ManifestEntry entry) throws Exception {
        Path fixture = SAMPLES.resolve(entry.fixture()).normalize();
        Path sidecar = sidecarPath(fixture, entry.sidecar());
        assertThat(fixture).as("fixture %s", entry.fixture()).isRegularFile();
        assertThat(sidecar).as("reference sidecar for %s", entry.fixture()).isRegularFile();
        assertThat(sha256Hex(sidecar)).as("manifest hash for %s", entry.fixture())
                .isEqualToIgnoringCase(entry.sha256());

        ReferenceImage reference = ReferenceImageLoader.load(sidecar);
        assertThat(reference.width()).as("reference width for %s", entry.fixture())
                .isEqualTo(entry.width());
        assertThat(reference.height()).as("reference height for %s", entry.fixture())
                .isEqualTo(entry.height());

        List<SubbandExtractor.SubbandRef> refs = SubbandExtractor.list(fixture.toFile()).stream()
                .filter(ref -> ref.waveletLevels() == 0 && ref.subbandIndex() == 0)
                .sorted(Comparator.comparingInt(SubbandExtractor.SubbandRef::tileSequence)
                        .thenComparingInt(SubbandExtractor.SubbandRef::planeIndex))
                .toList();
        assertThat(refs).as("lossless single-subband records for %s", entry.fixture())
                .isNotEmpty();

        Map<Integer, List<SubbandExtractor.SubbandRef>> byTile = refs.stream()
                .collect(Collectors.groupingBy(
                        SubbandExtractor.SubbandRef::tileSequence,
                        TreeMap::new,
                        Collectors.toList()));
        int fullPlaneWidth = (reference.width() + 1) / 2;
        int fullPlaneHeight = (reference.height() + 1) / 2;
        int tileXOffset = 0;
        int tileOrdinal = 0;
        List<ReferenceGuidedFirstDivergenceAnalyzer.AnalysisResult> streams =
                new ArrayList<>(refs.size() * 2);

        for (List<SubbandExtractor.SubbandRef> sourceTile : byTile.values()) {
            List<SubbandExtractor.SubbandRef> tile = new ArrayList<>(sourceTile);
            tile.sort(Comparator.comparingInt(SubbandExtractor.SubbandRef::planeIndex));
            assertThat(tile).as("four planes for tile in %s", entry.fixture()).hasSize(4);

            int remaining = fullPlaneWidth - tileXOffset;
            int planeWidth = tileOrdinal == byTile.size() - 1
                    ? remaining
                    : Math.min(tile.getFirst().planeWidth(), remaining);
            int planeHeight = Math.min(tile.getFirst().planeHeight(), fullPlaneHeight);
            assertThat(planeWidth).isPositive();
            assertThat(planeHeight).isPositive();

            for (SubbandExtractor.SubbandRef ref : tile) {
                assertThat(ref.planeIndex()).isBetween(0, 3);
                assertThat(entry.bitDepth())
                        .as("TIFF storage depth must hold CRX samples for %s", entry.fixture())
                        .isGreaterThanOrEqualTo(ref.bitDepth());
                var mapping = new ReferenceGuidedFirstDivergenceAnalyzer.PlaneMapping(
                        reference.width(),
                        reference.height(),
                        ref.tileSequence(),
                        ref.planeIndex(),
                        tileXOffset,
                        planeWidth,
                        planeHeight);
                byte[] payload = SubbandExtractor.read(fixture.toFile(), ref);
                CrxNormativeLosslessCandidate.Options baseline = baselineOptions(ref.bitDepth());
                for (var mode : ReferenceGuidedFirstDivergenceAnalyzer.InitializationMode.values()) {
                    streams.add(ReferenceGuidedFirstDivergenceAnalyzer.analyzePlane(
                            payload,
                            mapping,
                            ref.bitDepth(),
                            reference.samples(),
                            mode,
                            baseline));
                }
            }
            tileXOffset += planeWidth;
            tileOrdinal++;
        }
        assertThat(tileXOffset).as("tile coverage for %s", entry.fixture())
                .isEqualTo(fullPlaneWidth);

        return new ReferenceGuidedFirstDivergenceReport.FixtureResult(
                entry.fixture(),
                entry.tool(),
                entry.version(),
                entry.sha256(),
                reference.width(),
                reference.height(),
                streams);
    }

    private static CrxNormativeLosslessCandidate.Options baselineOptions(int bitDepth) {
        CrxNormativeLosslessCandidate.Options options =
                new CrxNormativeLosslessCandidate.Options();
        options.kInit = 1;
        options.resetKPerRow = true;
        options.unaryOnesUntilZero = true;
        options.signBitMapping = true;
        options.escapeAtUnary15 = false;
        options.firstPixelSeed = 0;
        options.escapeBitWidth = bitDepth;
        options.kAdaptation =
                CrxNormativeLosslessCandidate.KAdaptation.HYBRID_ACCUMULATOR;
        return options;
    }

    private static List<ManifestEntry> parseRawLinearManifestEntries() throws IOException {
        List<String> lines = Files.readAllLines(MANIFEST, StandardCharsets.UTF_8);
        int start = lines.indexOf(TSV_START);
        int end = lines.indexOf(TSV_END);
        assertThat(start).as("manifest start marker in %s", MANIFEST).isNotNegative();
        assertThat(end).as("manifest end marker in %s", MANIFEST).isGreaterThan(start);

        List<ManifestEntry> entries = new ArrayList<>();
        for (int index = start + 1; index < end; index++) {
            String line = lines.get(index).trim();
            if (line.isEmpty() || line.startsWith("```")
                    || line.startsWith("fixture|sidecar|")) {
                continue;
            }
            String[] columns = line.split("\\|", -1);
            assertThat(columns).as("manifest columns: %s", line).hasSize(11);
            String sidecar = columns[1].trim();
            String referenceType = columns[9].trim();
            boolean geometryMatches = Boolean.parseBoolean(columns[10].trim());
            if (!sidecar.equals("reference.tiff")
                    || !referenceType.equals("RAW_LINEAR")
                    || !geometryMatches) {
                continue;
            }
            entries.add(new ManifestEntry(
                    columns[0].trim(),
                    sidecar,
                    columns[2].trim(),
                    columns[3].trim(),
                    columns[5].trim().toLowerCase(Locale.ROOT),
                    Integer.parseInt(columns[6].trim()),
                    Integer.parseInt(columns[7].trim()),
                    Integer.parseInt(columns[8].trim())));
        }
        entries.sort(Comparator.comparing(ManifestEntry::fixture));
        assertThat(entries).allSatisfy(entry ->
                assertThat(entry.tool()).as("black-box reference tool").isEqualTo("LibRaw"));
        return List.copyOf(entries);
    }

    private static Path sidecarPath(Path fixture, String sidecar) {
        String name = fixture.getFileName().toString();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".cr3")) {
            throw new IllegalArgumentException("Not a CR3 fixture: " + fixture);
        }
        return fixture.resolveSibling(name.replaceFirst("(?i)\\.cr3$", "." + sidecar));
    }

    private static String sha256Hex(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException("Failed hashing " + path, e);
        }
    }

    private record ManifestEntry(
            String fixture,
            String sidecar,
            String tool,
            String version,
            String sha256,
            int width,
            int height,
            int bitDepth) {
    }
}
