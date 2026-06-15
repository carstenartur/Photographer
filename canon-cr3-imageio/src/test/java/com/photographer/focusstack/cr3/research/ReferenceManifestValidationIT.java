package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.container.Cr3Track;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ReferenceManifestValidationIT {

    private static final Path SAMPLES_DIR = Path.of("src", "test", "resources", "samples");
    private static final Path MANIFEST_DOC = Path.of("..", "docs", "cr3-reference-manifest.md");
    private static final String TSV_START = "<!-- reference-manifest-tsv:start -->";
    private static final String TSV_END = "<!-- reference-manifest-tsv:end -->";
    private static final Set<String> ALLOWED_TOOLS = Set.of("LibRaw", "dcraw_emu", "Adobe", "Canon DPP");

    @Test
    void validateReferenceManifest() throws Exception {
        List<ManifestEntry> entries = parseManifestEntries();
        assertThat(entries).isNotEmpty();

        Map<Path, Geometry> geometryByFixture = new HashMap<>();
        Set<String> manifestSidecars = new HashSet<>();

        for (ManifestEntry entry : entries) {
            Path fixture = SAMPLES_DIR.resolve(entry.fixture());
            Path sidecar = toSidecarPath(fixture, entry.sidecar());
            String relativeSidecar = SAMPLES_DIR.relativize(sidecar).toString().replace('\\', '/');
            manifestSidecars.add(relativeSidecar);

            assertThat(Files.isRegularFile(fixture)).as("fixture exists: %s", entry.fixture()).isTrue();
            assertThat(Files.isRegularFile(sidecar)).as("sidecar exists: %s", relativeSidecar).isTrue();
            assertThat(ALLOWED_TOOLS).as("allowed tool for %s", relativeSidecar).contains(entry.tool());
            assertThat(entry.version()).as("version for %s", relativeSidecar).isNotBlank();
            assertThat(entry.command()).as("command for %s", relativeSidecar).isNotBlank();

            Geometry crxGeometry = geometryByFixture.computeIfAbsent(fixture, ReferenceManifestValidationIT::readCrxBigGeometry);

            BufferedImage image = ImageIO.read(sidecar.toFile());
            assertThat(image).as("decodable sidecar: %s", relativeSidecar).isNotNull();
            assertThat(image.getWidth()).as("width for %s", relativeSidecar).isEqualTo(entry.width());
            assertThat(image.getHeight()).as("height for %s", relativeSidecar).isEqualTo(entry.height());

            int bitDepth = image.getRaster().getSampleModel().getSampleSize(0);
            assertThat(bitDepth).as("bit depth for %s", relativeSidecar).isEqualTo(entry.bitDepth());

            boolean inferredRawLinear = isRawLinearCandidate(image, crxGeometry);
            assertThat(inferredRawLinear)
                    .as("raw-linear classification for %s", relativeSidecar)
                    .isEqualTo(entry.rawLinear());

            if (entry.geometryMatchesCrxBig()) {
                assertThat(image.getWidth()).as("CRX_BIG width for %s", relativeSidecar).isEqualTo(crxGeometry.width());
                assertThat(image.getHeight()).as("CRX_BIG height for %s", relativeSidecar).isEqualTo(crxGeometry.height());
            } else {
                assertThat(image.getWidth() == crxGeometry.width() && image.getHeight() == crxGeometry.height())
                        .as("CRX_BIG geometry mismatch expected for %s", relativeSidecar)
                        .isFalse();
            }

            String actualHash = sha256Hex(sidecar);
            assertThat(actualHash)
                    .as("sha256 for %s", relativeSidecar)
                    .isEqualToIgnoringCase(entry.sha256());

            if (entry.sidecar().endsWith(".tiff")) {
                assertThat(entry.rawLinear()).as("TIFF must be RAW-linear: %s", relativeSidecar).isTrue();
                assertThat(entry.geometryMatchesCrxBig()).as("TIFF geometry must match CRX_BIG: %s", relativeSidecar).isTrue();
            }
            if (entry.sidecar().endsWith(".png")) {
                assertThat(entry.rawLinear()).as("PNG is preview/rendered: %s", relativeSidecar).isFalse();
            }
        }

        Set<String> discoveredSidecars = discoverReferenceSidecars();
        assertThat(manifestSidecars)
                .as("manifest sidecars must match discovered sidecars")
                .isEqualTo(discoveredSidecars);
    }

    private static List<ManifestEntry> parseManifestEntries() throws IOException {
        List<String> lines = Files.readAllLines(MANIFEST_DOC, StandardCharsets.UTF_8);
        int start = lines.indexOf(TSV_START);
        int end = lines.indexOf(TSV_END);
        assertThat(start).as("manifest start marker").isNotNegative();
        assertThat(end).as("manifest end marker").isGreaterThan(start);

        List<String> tsvLines = new ArrayList<>();
        for (int i = start + 1; i < end; i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("```") || line.startsWith("fixture|sidecar|")) {
                continue;
            }
            tsvLines.add(line);
        }

        List<ManifestEntry> entries = new ArrayList<>();
        for (String line : tsvLines) {
            String[] cols = line.split("\\|", -1);
            assertThat(cols.length).as("column count in line: %s", line).isEqualTo(11);
            entries.add(new ManifestEntry(
                    cols[0],
                    cols[1],
                    cols[2],
                    cols[3],
                    cols[4],
                    cols[5].toLowerCase(Locale.ROOT),
                    Integer.parseInt(cols[6]),
                    Integer.parseInt(cols[7]),
                    Integer.parseInt(cols[8]),
                    Boolean.parseBoolean(cols[9]),
                    Boolean.parseBoolean(cols[10])
            ));
        }
        return entries;
    }

    private static Geometry readCrxBigGeometry(Path fixture) {
        try (ImageInputStream in = ImageIO.createImageInputStream(fixture.toFile())) {
            assertThat(in).as("input stream for %s", fixture).isNotNull();
            Cr3Container container = Cr3Parser.parse(in);
            Cr3Track crxBig = container.tracks().stream()
                    .filter(track -> track.type() == Cr3Track.TrackType.CRX_BIG)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No CRX_BIG track in " + fixture));
            int width = crxBig.cmp1() != null && crxBig.cmp1().imageWidth() > 0
                    ? crxBig.cmp1().imageWidth()
                    : crxBig.width();
            int height = crxBig.cmp1() != null && crxBig.cmp1().imageHeight() > 0
                    ? crxBig.cmp1().imageHeight()
                    : crxBig.height();
            return new Geometry(width, height);
        } catch (Exception e) {
            throw new IllegalStateException("Failed reading CRX_BIG geometry for " + fixture, e);
        }
    }

    private static Path toSidecarPath(Path fixture, String sidecar) {
        String replacement = "." + sidecar;
        return fixture.resolveSibling(fixture.getFileName().toString().replaceFirst("(?i)\\.cr3$", replacement));
    }

    private static boolean isRawLinearCandidate(BufferedImage image, Geometry crxGeometry) {
        int bands = image.getRaster().getNumBands();
        int sampleSize = image.getRaster().getSampleModel().getSampleSize(0);
        return bands == 1
                && sampleSize >= 12
                && image.getWidth() == crxGeometry.width()
                && image.getHeight() == crxGeometry.height();
    }

    private static String sha256Hex(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = Files.readAllBytes(path);
            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException("Failed computing sha256 for " + path, e);
        }
    }

    private static Set<String> discoverReferenceSidecars() throws IOException {
        try (Stream<Path> stream = Files.walk(SAMPLES_DIR)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(SAMPLES_DIR::relativize)
                    .map(path -> path.toString().replace('\\', '/'))
                    .filter(path -> path.endsWith(".reference.tiff") || path.endsWith(".reference.png"))
                    .collect(java.util.stream.Collectors.toSet());
        }
    }

    private record ManifestEntry(String fixture,
                                 String sidecar,
                                 String tool,
                                 String version,
                                 String command,
                                 String sha256,
                                 int width,
                                 int height,
                                 int bitDepth,
                                 boolean rawLinear,
                                 boolean geometryMatchesCrxBig) {
    }

    private record Geometry(int width, int height) {
    }
}
