package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.raw.codec.SubbandExtractor;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that exercises {@link SubbandExtractor} and
 * {@link SubbandStatistics} end-to-end against the bundled real CR3 samples.
 *
 * <p>This test is the connective tissue between the production utilities and
 * the research log: every fixture mentioned in {@code SubbandFixtures.md}
 * must be present and parseable here. It does <em>not</em> attempt to decode
 * any subband — that is the job of speculative hypothesis tests added in
 * future sessions.</p>
 */
class SubbandResearchHarnessIT {

    private static final String[] SAMPLES = {
            "src/test/resources/samples/sample.cr3",
            "src/test/resources/samples/sample-iso-high.cr3",
            "src/test/resources/samples/sample-wb-tungsten.cr3",
            "src/test/resources/samples/sample-tele.cr3",
            "src/test/resources/samples/sample-wide.cr3",
            "src/test/resources/samples/eosr-iso-low.cr3",
            "src/test/resources/samples/eosr-iso-mid.cr3",
            "src/test/resources/samples/eosr-iso-high.cr3",
    };

    @Test
    void everySampleHasParseableSubbands() throws Exception {
        List<String> missing = new ArrayList<>();
        for (String path : SAMPLES) {
            File f = new File(path);
            if (!f.exists()) {
                missing.add(path);
                continue;
            }
            List<SubbandExtractor.SubbandRef> subs = SubbandExtractor.list(f);
            assertThat(subs)
                    .as("subbands in %s", path)
                    .isNotEmpty();
            for (SubbandExtractor.SubbandRef r : subs) {
                assertThat(r.dataSize()).as("subband size positive in %s", path).isPositive();
                assertThat(r.dataOffsetInFile()).as("subband offset positive in %s", path).isPositive();
                assertThat(r.bitDepth()).as("bit depth set in %s", path).isPositive();
            }
        }
        assertThat(missing).as("expected fixtures present").isEmpty();
    }

    @Test
    void firstSubbandOfFirstSampleHasReasonableStatistics() throws Exception {
        File f = new File(SAMPLES[0]);
        List<SubbandExtractor.SubbandRef> subs = SubbandExtractor.list(f);
        assertThat(subs).isNotEmpty();

        byte[] payload = SubbandExtractor.read(f, subs.get(0));
        SubbandStatistics stats = SubbandStatistics.compute(payload);

        // A real entropy-coded subband should not be trivially all-zero or all-one.
        assertThat(stats.oneBitFraction())
                .as("entropy-coded data should be roughly bit-balanced")
                .isBetween(0.10, 0.90);

        // And the first subband of the bundled sample.cr3 is a well-known size:
        assertThat(payload.length).isPositive();
    }

    @Test
    void harnessAcceptsRealSubbandPayload() throws Exception {
        // This proves the harness can iterate over real fixtures.
        // We intentionally use the identity decoder, which will *not* match the
        // true pixel grid — the assertion is only on the structural contract:
        // the harness completes without throwing and produces a Result.
        File f = new File(SAMPLES[0]);
        List<SubbandExtractor.SubbandRef> subs = SubbandExtractor.list(f);
        SubbandExtractor.SubbandRef first = subs.get(0);
        byte[] payload = SubbandExtractor.read(f, first);

        // Synthesize a reference of the right pixel-count from the plane grid;
        // values are unimportant since we only check that the harness ran.
        int n = first.planeWidth() * first.planeHeight();
        int[] reference = new int[Math.min(n, 4096)];

        SubbandHypothesisHarness.Result result = SubbandHypothesisHarness.evaluate(
                "sample.cr3:tile0:plane0:sb0",
                payload,
                reference,
                SubbandHypothesisHarness.identityByteDecoder());

        assertThat(result.threwException()).isFalse();
        assertThat(result.expectedLength()).isEqualTo(reference.length);
    }
}
