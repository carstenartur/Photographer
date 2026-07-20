package com.photographer.focusstack.cr3.research;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CrxSubbandTopologyReportTest {

    @Test
    void serializesDeterministicallyWithoutInventingPayloadOffsets() {
        List<CrxSubbandTopologyReport.Row> rows = List.of(
                row("eosr-iso-low.cr3", "EOS_R", 0, 0, 0,
                        1, 0, 128, 96, 14, 1024,
                        "r1:t0:p0:l2:s0", "", "FIRST_TILE,FIRST_PLANE"),
                row("research/g5xm2-dark.cr3", "G5_X_MARK_II_RESEARCH",
                        1, 0, 1, 2, 0, 64, 48, 14, 512,
                        "", "", "LAST_TILE"));

        String markdown = CrxSubbandTopologyReport.markdown(rows);
        String tsv = CrxSubbandTopologyReport.tsv(rows);
        String json = CrxSubbandTopologyReport.json(rows);

        assertThat(CrxSubbandTopologyReport.markdown(rows)).isEqualTo(markdown);
        assertThat(CrxSubbandTopologyReport.tsv(rows)).isEqualTo(tsv);
        assertThat(CrxSubbandTopologyReport.json(rows)).isEqualTo(json);
        assertThat(tsv).contains("payloadOffset").contains("NOT_EXPOSED");
        assertThat(json).contains("\"payloadOffset\":null");
        assertThat(markdown.indexOf("eosr-iso-low.cr3"))
                .isLessThan(markdown.indexOf("research/g5xm2-dark.cr3"));
    }

    @Test
    void countsCommaSeparatedCandidatesWithoutTreatingEmptyAsOne() {
        List<CrxSubbandTopologyReport.Row> rows = List.of(
                row("eosr-iso-low.cr3", "EOS_R", 0, 0, 0,
                        1, 0, 128, 96, 14, 1024,
                        "r1:t0:p0:l2:s0,r2:t0:p0:l2:s1",
                        "r1:t0:p0:l2:s0", "FIRST_TILE"),
                row("sample.cr3", "SAMPLE_SERIES", 0, 0, 0,
                        0, 0, 256, 192, 14, 2048,
                        "", "", "LEVEL_ZERO"));

        String json = CrxSubbandTopologyReport.json(rows);

        assertThat(json).contains("\"dimensionCandidates\": 2")
                .contains("\"causalCandidates\": 1");
    }

    private static CrxSubbandTopologyReport.Row row(
            String fixture, String family, int order,
            int tile, int plane, int levels, int subband,
            int width, int height, int bitDepth, int payloadBytes,
            String dimensionCandidates, String causalCandidates,
            String boundaries) {
        return new CrxSubbandTopologyReport.Row(
                fixture, family, order, tile, plane, levels, subband,
                width, height, bitDepth, payloadBytes,
                dimensionCandidates, causalCandidates, boundaries);
    }
}
