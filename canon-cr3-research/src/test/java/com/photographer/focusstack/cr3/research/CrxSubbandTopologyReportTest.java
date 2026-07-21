package com.photographer.focusstack.cr3.research;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CrxSubbandTopologyReportTest {

    @Test
    void serializesDeterministicallyWithExposedPayloadRanges() {
        List<CrxSubbandTopologyReport.Row> rows = List.of(
                row("research/g5xm2-dark.cr3", "G5_X_MARK_II_RESEARCH",
                        1, 0, 0, 1, 0, 0,
                        64, 48, 14, 2048, 512,
                        "", "NOT_APPLICABLE_WAVELET_LEVELS_0",
                        "CONTIGUOUS_WITH_PREVIOUS", "LAST_TILE,LAST_PLANE"),
                row("eosr-iso-low.cr3", "EOS_R",
                        0, 0, 0, 0, 0, 0,
                        128, 96, 14, 1024, 1024,
                        "", "NOT_APPLICABLE_WAVELET_LEVELS_0",
                        "FIRST_RECORD", "FIRST_TILE,FIRST_PLANE"));

        String markdown = CrxSubbandTopologyReport.markdown(rows);
        String tsv = CrxSubbandTopologyReport.tsv(rows);
        String json = CrxSubbandTopologyReport.json(rows);

        assertThat(CrxSubbandTopologyReport.markdown(rows)).isEqualTo(markdown);
        assertThat(CrxSubbandTopologyReport.tsv(rows)).isEqualTo(tsv);
        assertThat(CrxSubbandTopologyReport.json(rows)).isEqualTo(json);
        assertThat(tsv)
                .contains("payloadOffset\tpayloadSize\tpayloadEndExclusive")
                .doesNotContain("NOT_EXPOSED");
        assertThat(json)
                .contains("\"payloadOffset\":1024")
                .contains("\"payloadEndExclusive\":2048")
                .contains("\"causalAssessment\":\"NOT_APPLICABLE_WAVELET_LEVELS_0\"");
        assertThat(markdown.indexOf("eosr-iso-low.cr3"))
                .isLessThan(markdown.indexOf("research/g5xm2-dark.cr3"));
    }

    @Test
    void reportsTopologyVariantsPerFamilyInsteadOfInvalidSetArithmetic() {
        List<CrxSubbandTopologyReport.Row> rows = List.of(
                row("eosr-a.cr3", "EOS_R", 0,
                        0, 0, 0, 0, 0, 128, 96, 14,
                        100, 10, "", "NOT_APPLICABLE_WAVELET_LEVELS_0",
                        "FIRST_RECORD", "FIRST_TILE"),
                row("eosr-b.cr3", "EOS_R", 0,
                        0, 0, 0, 0, 0, 128, 96, 14,
                        200, 20, "", "NOT_APPLICABLE_WAVELET_LEVELS_0",
                        "FIRST_RECORD", "FIRST_TILE"),
                row("g5.cr3", "G5_X_MARK_II_RESEARCH", 0,
                        0, 0, 0, 0, 0, 64, 48, 14,
                        300, 30, "", "NOT_APPLICABLE_WAVELET_LEVELS_0",
                        "FIRST_RECORD", "FIRST_TILE"));

        CrxSubbandTopologyReport.Summary summary =
                CrxSubbandTopologyReport.summary(rows);

        assertThat(summary.fixtures()).isEqualTo(3);
        assertThat(summary.distinctTopologyVariants()).isEqualTo(2);
        assertThat(summary.familiesWithInternalVariation()).isZero();
        assertThat(summary.crossFamilyUniform()).isFalse();
        assertThat(summary.families())
                .extracting(CrxSubbandTopologyReport.FamilySummary::topologyVariants)
                .containsOnly(1);
    }

    @Test
    void countsContinuityAndUnresolvedMappingSeparately() {
        List<CrxSubbandTopologyReport.Row> rows = List.of(
                row("lossless.cr3", "SAMPLE_SERIES", 0,
                        0, 0, 0, 0, 0, 128, 96, 14,
                        100, 10, "", "NOT_APPLICABLE_WAVELET_LEVELS_0",
                        "FIRST_RECORD", "FIRST_TILE"),
                row("lossy.cr3", "SAMPLE_SERIES", 0,
                        0, 0, 0, 3, 1, 128, 96, 14,
                        200, 20, "UNRESOLVED_UNDECOMPOSED_PLANE_DIMENSIONS",
                        "UNRESOLVED_NO_PER_SUBBAND_GEOMETRY_OR_ROLE",
                        "GAP_FROM_PREVIOUS_4", "FIRST_TILE"),
                row("lossy.cr3", "SAMPLE_SERIES", 1,
                        0, 0, 0, 3, 2, 128, 96, 14,
                        220, 10, "UNRESOLVED_UNDECOMPOSED_PLANE_DIMENSIONS",
                        "UNRESOLVED_NO_PER_SUBBAND_GEOMETRY_OR_ROLE",
                        "CONTIGUOUS_WITH_PREVIOUS", "LAST_TILE"));

        CrxSubbandTopologyReport.Summary summary =
                CrxSubbandTopologyReport.summary(rows);

        assertThat(summary.losslessSingleSubbandRecords()).isEqualTo(1);
        assertThat(summary.unresolvedParentRecords()).isEqualTo(2);
        assertThat(summary.contiguousTransitions()).isEqualTo(1);
        assertThat(summary.gaps()).isEqualTo(1);
        assertThat(summary.overlaps()).isZero();
        assertThat(summary.totalPayloadBytes()).isEqualTo(40);
    }

    private static CrxSubbandTopologyReport.Row row(
            String fixture, String family, int order,
            int tileIndex, int tileSequence, int plane,
            int levels, int subband,
            int width, int height, int bitDepth,
            long offset, long size,
            String dimensionCandidates, String causalAssessment,
            String continuity, String boundaries) {
        return new CrxSubbandTopologyReport.Row(
                fixture, family, order, tileIndex, tileSequence, plane,
                levels, subband, width, height, bitDepth,
                offset, size, offset + size,
                dimensionCandidates, causalAssessment, continuity, boundaries);
    }
}
