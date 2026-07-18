package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.reference.ReferenceImage;
import com.photographer.focusstack.cr3.reference.ReferenceImageLoader;
import com.photographer.focusstack.cr3.reference.ReferenceMetrics;
import com.photographer.focusstack.cr3.reference.ReferencePaths;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Proves that ImageIO research tests consume the shared reference testkit. */
class ReferenceTestkitIntegrationTest {

    private static final Path SAMPLES_DIR = Path.of("src", "test", "resources", "samples");

    @Test
    void loadsCommittedRawLinearReferenceAndUsesCanonicalMetrics() throws Exception {
        Path fixture;
        try (var paths = Files.walk(SAMPLES_DIR)) {
            fixture = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".cr3"))
                    .filter(ReferencePaths::hasRawLinearTiff)
                    .sorted(Comparator.comparing(path -> SAMPLES_DIR.relativize(path).toString()))
                    .findFirst()
                    .orElse(null);
        }
        assumeTrue(fixture != null, "No committed CR3 fixture with RAW-linear reference TIFF found");

        ReferenceImage reference = ReferenceImageLoader.load(ReferencePaths.rawLinearTiff(fixture));
        ReferenceMetrics.MetricTriple identity = ReferenceMetrics.compare(
                reference.samples(),
                reference.samples().clone());

        assertThat(reference.width()).isPositive();
        assertThat(reference.height()).isPositive();
        assertThat(reference.samples()).hasSize(reference.width() * reference.height());
        assertThat(identity.mae()).isZero();
        assertThat(identity.rmse()).isZero();
        assertThat(identity.pearson()).isEqualTo(1.0);
    }
}
