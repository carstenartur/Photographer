package com.photographer.focusstack.cr3.cli;

import com.photographer.focusstack.cr3.decode.Cr3Decoder;
import com.photographer.focusstack.cr3.metadata.Cr3Metadata;
import com.photographer.focusstack.cr3.processing.BlackLevel;
import com.photographer.focusstack.cr3.raw.codec.CrxHeader;
import com.photographer.focusstack.cr3.raw.codec.SubbandExtractor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the CRX research CLI helpers in {@link Cr3CliApp}: the JSON
 * formatter and the option-parsing edges that are exercised by
 * {@code dump-subband} and {@code subband-stats}. End-to-end tests against
 * real CR3 samples live in {@code canon-cr3-imageio} where the samples reside.
 */
class Cr3CliAppSubbandTest {

    @Test
    void jsonContainsAllRecordFields() {
        SubbandExtractor.SubbandRef ref = new SubbandExtractor.SubbandRef(
                3, 1, 2, 0,
                12345L, 6789L,
                4, true, 1,
                5632, 3710, 14,
                CrxHeader.CodecVersion.V2,
                2820, 1856, 1410, 928,
                3, 1,
                "CanonCR3_002/01.00.00/00.00.00");

        String json = Cr3CliApp.formatSubbandMetaJson("/tmp/sample.cr3", 7, ref);

        assertThat(json)
                .contains("\"sourceFile\": \"/tmp/sample.cr3\"")
                .contains("\"sequence\": 7")
                .contains("\"tileIndex\": 3")
                .contains("\"tileSequence\": 1")
                .contains("\"planeIndex\": 2")
                .contains("\"subbandIndex\": 0")
                .contains("\"dataOffsetInFile\": 12345")
                .contains("\"dataSize\": 6789")
                .contains("\"quantValue\": 4")
                .contains("\"supportsPartial\": true")
                .contains("\"roundedBits\": 1")
                .contains("\"trackWidth\": 5632")
                .contains("\"trackHeight\": 3710")
                .contains("\"bitDepth\": 14")
                .contains("\"codecVersion\": \"V2\"")
                .contains("\"tileWidth\": 2820")
                .contains("\"tileHeight\": 1856")
                .contains("\"planeWidth\": 1410")
                .contains("\"planeHeight\": 928")
                .contains("\"waveletLevels\": 3")
                .contains("\"encodingType\": 1")
                .contains("\"cncvVersion\": \"CanonCR3_002/01.00.00/00.00.00\"");
        assertThat(json).startsWith("{").endsWith("}\n");
    }

    @Test
    void jsonHandlesNullCncvVersion() {
        SubbandExtractor.SubbandRef ref = new SubbandExtractor.SubbandRef(
                0, 0, 0, 0,
                0L, 0L,
                0, false, 0,
                0, 0, 14, CrxHeader.CodecVersion.V1,
                0, 0, 0, 0, 0, 0, null);

        String json = Cr3CliApp.formatSubbandMetaJson("x", 0, ref);
        assertThat(json).contains("\"cncvVersion\": null");
    }

    @Test
    void jsonEscapesSpecialCharactersInPath() {
        SubbandExtractor.SubbandRef ref = new SubbandExtractor.SubbandRef(
                0, 0, 0, 0, 0L, 0L, 0, false, 0,
                0, 0, 14, CrxHeader.CodecVersion.V1,
                0, 0, 0, 0, 0, 0, "v");
        String json = Cr3CliApp.formatSubbandMetaJson("path with \"quotes\"\nand newline", 0, ref);
        assertThat(json).contains("\"sourceFile\": \"path with \\\"quotes\\\"\\nand newline\"");
    }

    @Test
    void pedestalReportIncludesResolvedSourceAndLevels() {
        Cr3Metadata metadata = new Cr3Metadata();
        metadata.setIsoSpeed(400);
        metadata.setActiveAreaLeft(128);
        metadata.setActiveAreaTop(40);
        metadata.setActiveAreaRight(5000);
        metadata.setActiveAreaBottom(3400);
        metadata.setPerChannelBlackLevels(new int[]{2048, 2056, 2054, 2060});

        Cr3Decoder.PedestalReport report = new Cr3Decoder.PedestalReport(
                Cr3Decoder.PedestalSource.METADATA_PER_CHANNEL,
                BlackLevel.perChannel(2048, 2056, 2054, 2060),
                128,
                4,
                new int[]{2048, 2056, 2054, 2060},
                5632,
                3710,
                9,
                2045);

        String output = Cr3CliApp.formatPedestalReport("sample.cr3", metadata, report);

        assertThat(output)
                .contains("=== Pedestal Report ===")
                .contains("Resolved source: METADATA_PER_CHANNEL")
                .contains("Metadata per-channel [R,Gr,Gb,B]: [2048, 2056, 2054, 2060]")
                .contains("Resolved black level mode: PER_CHANNEL")
                .contains("Resolved per-channel [R,Gr,Gb,B]: [2048, 2056, 2054, 2060]");
    }
}
