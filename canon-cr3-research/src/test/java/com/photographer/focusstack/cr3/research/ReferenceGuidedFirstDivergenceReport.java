package com.photographer.focusstack.cr3.research;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Deterministic Markdown, TSV and JSON output for reference-guided traces. */
final class ReferenceGuidedFirstDivergenceReport {

    record FixtureResult(
            String fixture,
            String referenceTool,
            String referenceVersion,
            String referenceSha256,
            int width,
            int height,
            List<ReferenceGuidedFirstDivergenceAnalyzer.AnalysisResult> streams) {

        FixtureResult {
            Objects.requireNonNull(fixture, "fixture");
            Objects.requireNonNull(referenceTool, "referenceTool");
            Objects.requireNonNull(referenceVersion, "referenceVersion");
            Objects.requireNonNull(referenceSha256, "referenceSha256");
            Objects.requireNonNull(streams, "streams");
            if (width <= 0 || height <= 0 || streams.isEmpty()) {
                throw new IllegalArgumentException("Invalid fixture report");
            }
            streams = List.copyOf(streams);
        }

        @Override
        public List<ReferenceGuidedFirstDivergenceAnalyzer.AnalysisResult> streams() {
            return streams;
        }
    }

    private ReferenceGuidedFirstDivergenceReport() {
    }

    static String markdown(List<FixtureResult> input) {
        List<FixtureResult> fixtures = sorted(input);
        StringBuilder out = new StringBuilder();
        out.append("# LibRaw-guided first CRX reference divergence\n\n")
                .append("The RAW-linear TIFF values are registered black-box reference output. ")
                .append("They are used only to identify the first disagreement with the unchanged ")
                .append("test-scope decoder and to derive reference MED context.\n\n")
                .append("Two initialization modes are reported: `CONFIGURED_BASELINE` preserves ")
                .append("the historical test decoder seed, while `REFERENCE_FIRST_SAMPLE_ORACLE` ")
                .append("uses the first correct sample only to neutralize the already-known ")
                .append("DC/pedestal mismatch and locate the next contradiction. The oracle mode ")
                .append("is diagnostic evidence, not a decoder proposal.\n\n")
                .append("## Stream summary\n\n")
                .append("| Fixture | Tile | Plane | Mode | Seed | Classification | Matched prefix | Symbol | Full coordinate | Bit range | Completion |\n")
                .append("|---|---:|---:|---|---:|---|---:|---:|---|---|---:|\n");

        for (FixtureResult fixture : fixtures) {
            for (var stream : sortedStreams(fixture.streams())) {
                var divergence = stream.firstDivergence().orElse(null);
                out.append("| `").append(fixture.fixture()).append("` | ")
                        .append(stream.mapping().tileSequence()).append(" | ")
                        .append(stream.mapping().planeIndex()).append(" | ")
                        .append(stream.initializationMode()).append(" | ")
                        .append(stream.firstPixelSeed()).append(" | ")
                        .append(stream.classification()).append(" | ")
                        .append(stream.matchedPrefixSymbols()).append(" | ")
                        .append(divergence == null ? "—" : divergence.trace().ordinal()).append(" | ")
                        .append(divergence == null ? "—"
                                : divergence.fullSensorX() + "," + divergence.fullSensorY())
                        .append(" | ")
                        .append(divergence == null ? "—"
                                : divergence.trace().bitStart() + ".." + divergence.trace().bitEnd())
                        .append(" | ")
                        .append(format(stream.completion())).append(" |\n");
            }
        }

        out.append("\n## First-divergence context\n");
        for (FixtureResult fixture : fixtures) {
            out.append("\n### `").append(fixture.fixture()).append("`\n\n")
                    .append("Reference: ").append(fixture.referenceTool()).append(" ")
                    .append(fixture.referenceVersion()).append(", SHA-256 `")
                    .append(fixture.referenceSha256()).append("`\n\n");
            for (var stream : sortedStreams(fixture.streams())) {
                out.append("#### Tile ").append(stream.mapping().tileSequence())
                        .append(", plane ").append(stream.mapping().planeIndex())
                        .append(", mode ").append(stream.initializationMode()).append("\n\n")
                        .append("Seed: ").append(stream.firstPixelSeed()).append(". ");
                if (stream.firstDivergence().isEmpty()) {
                    out.append("Result: **").append(stream.classification()).append("**; decoded ")
                            .append(stream.symbolsDecoded()).append("/").append(stream.capacity())
                            .append(" symbols, matched prefix ")
                            .append(stream.matchedPrefixSymbols())
                            .append(", consumed ").append(stream.consumedBytes())
                            .append(" bytes.\n\n");
                    continue;
                }
                out.append("Matched prefix: ").append(stream.matchedPrefixSymbols()).append(" symbols.\n\n");
                var d = stream.firstDivergence().orElseThrow();
                var t = d.trace();
                var r = d.reference();
                out.append("- Classification: **").append(d.classification()).append("**\n")
                        .append("- Symbol/local/full: ").append(t.ordinal()).append(" / ")
                        .append(d.localX()).append(",").append(d.localY()).append(" / ")
                        .append(d.fullSensorX()).append(",").append(d.fullSensorY()).append("\n")
                        .append("- Payload bits: ").append(t.bitStart()).append("..")
                        .append(t.bitEnd()).append("; K ").append(t.kBefore()).append("→")
                        .append(t.kAfter()).append("; q=").append(t.quotient())
                        .append("; remainder=").append(t.remainder()).append("\n")
                        .append("- Decoder: predictor=").append(t.predictor())
                        .append("; unsigned=").append(t.unsignedSymbol())
                        .append("; residual=").append(t.signedResidual())
                        .append("; reconstructed=").append(t.reconstructed()).append("\n")
                        .append("- Reference: W=").append(nullable(r.west()))
                        .append("; N=").append(nullable(r.north()))
                        .append("; NW=").append(nullable(r.northWest()))
                        .append("; predictor=").append(r.predictor())
                        .append("; unsigned=").append(r.expectedUnsignedSymbol())
                        .append("; residual=").append(r.expectedSignedResidual())
                        .append("; value=").append(r.referenceValue()).append("\n")
                        .append("- Signed reconstruction error: ").append(d.signedError()).append("\n\n");
            }
        }
        return out.toString();
    }

    static String tsv(List<FixtureResult> input) {
        StringBuilder out = new StringBuilder();
        out.append("fixture\treferenceTool\treferenceVersion\treferenceSha256\twidth\theight")
                .append("\ttile\tplane\tinitializationMode\tfirstPixelSeed\tclassification")
                .append("\tmatchedPrefixSymbols\tcapacity\tsymbolsDecoded\tcompletion")
                .append("\tstreamExhausted\tconsumedBytes\tsymbol\tlocalX\tlocalY\tfullX\tfullY")
                .append("\tbitStart\tbitEnd\tkBefore\tkAfter\tquotient\tremainder")
                .append("\tdecodedUnsigned\texpectedUnsigned\tdecodedResidual\texpectedResidual")
                .append("\tdecoderPredictor\treferencePredictor\treconstructed\treferenceValue")
                .append("\twest\tnorth\tnorthWest\tsignedError\n");
        for (FixtureResult fixture : sorted(input)) {
            for (var stream : sortedStreams(fixture.streams())) {
                var d = stream.firstDivergence().orElse(null);
                var t = d == null ? null : d.trace();
                var r = d == null ? null : d.reference();
                appendTsv(out,
                        fixture.fixture(), fixture.referenceTool(), fixture.referenceVersion(),
                        fixture.referenceSha256(), fixture.width(), fixture.height(),
                        stream.mapping().tileSequence(), stream.mapping().planeIndex(),
                        stream.initializationMode(), stream.firstPixelSeed(), stream.classification(),
                        stream.matchedPrefixSymbols(), stream.capacity(), stream.symbolsDecoded(),
                        format(stream.completion()), stream.streamExhausted(), stream.consumedBytes(),
                        t == null ? null : t.ordinal(), d == null ? null : d.localX(),
                        d == null ? null : d.localY(), d == null ? null : d.fullSensorX(),
                        d == null ? null : d.fullSensorY(), t == null ? null : t.bitStart(),
                        t == null ? null : t.bitEnd(), t == null ? null : t.kBefore(),
                        t == null ? null : t.kAfter(), t == null ? null : t.quotient(),
                        t == null ? null : t.remainder(), t == null ? null : t.unsignedSymbol(),
                        r == null ? null : r.expectedUnsignedSymbol(),
                        t == null ? null : t.signedResidual(),
                        r == null ? null : r.expectedSignedResidual(),
                        t == null ? null : t.predictor(), r == null ? null : r.predictor(),
                        t == null ? null : t.reconstructed(), r == null ? null : r.referenceValue(),
                        r == null ? null : r.west(), r == null ? null : r.north(),
                        r == null ? null : r.northWest(), d == null ? null : d.signedError());
            }
        }
        return out.toString();
    }

    static String json(List<FixtureResult> input) {
        List<FixtureResult> fixtures = sorted(input);
        StringBuilder out = new StringBuilder("{\n  \"fixtures\": [\n");
        for (int fixtureIndex = 0; fixtureIndex < fixtures.size(); fixtureIndex++) {
            FixtureResult fixture = fixtures.get(fixtureIndex);
            out.append("    {\n")
                    .append("      \"fixture\": \"").append(escape(fixture.fixture())).append("\",\n")
                    .append("      \"referenceTool\": \"").append(escape(fixture.referenceTool())).append("\",\n")
                    .append("      \"referenceVersion\": \"").append(escape(fixture.referenceVersion())).append("\",\n")
                    .append("      \"referenceSha256\": \"").append(fixture.referenceSha256()).append("\",\n")
                    .append("      \"width\": ").append(fixture.width()).append(",\n")
                    .append("      \"height\": ").append(fixture.height()).append(",\n")
                    .append("      \"streams\": [\n");
            List<ReferenceGuidedFirstDivergenceAnalyzer.AnalysisResult> streams =
                    sortedStreams(fixture.streams());
            for (int streamIndex = 0; streamIndex < streams.size(); streamIndex++) {
                appendStreamJson(out, streams.get(streamIndex));
                if (streamIndex + 1 < streams.size()) out.append(',');
                out.append('\n');
            }
            out.append("      ]\n    }");
            if (fixtureIndex + 1 < fixtures.size()) out.append(',');
            out.append('\n');
        }
        return out.append("  ]\n}\n").toString();
    }

    private static void appendStreamJson(
            StringBuilder out,
            ReferenceGuidedFirstDivergenceAnalyzer.AnalysisResult stream) {
        out.append("        {\n")
                .append("          \"tile\": ").append(stream.mapping().tileSequence()).append(",\n")
                .append("          \"plane\": ").append(stream.mapping().planeIndex()).append(",\n")
                .append("          \"initializationMode\": \"").append(stream.initializationMode()).append("\",\n")
                .append("          \"firstPixelSeed\": ").append(stream.firstPixelSeed()).append(",\n")
                .append("          \"classification\": \"").append(stream.classification()).append("\",\n")
                .append("          \"matchedPrefixSymbols\": ").append(stream.matchedPrefixSymbols()).append(",\n")
                .append("          \"capacity\": ").append(stream.capacity()).append(",\n")
                .append("          \"symbolsDecoded\": ").append(stream.symbolsDecoded()).append(",\n")
                .append("          \"completion\": ").append(format(stream.completion())).append(",\n")
                .append("          \"streamExhausted\": ").append(stream.streamExhausted()).append(",\n")
                .append("          \"consumedBytes\": ").append(stream.consumedBytes()).append(",\n")
                .append("          \"firstDivergence\": ");
        if (stream.firstDivergence().isEmpty()) {
            out.append("null\n        }");
            return;
        }
        var d = stream.firstDivergence().orElseThrow();
        var t = d.trace();
        var r = d.reference();
        out.append("{\n")
                .append("            \"symbol\": ").append(t.ordinal()).append(",\n")
                .append("            \"localX\": ").append(d.localX()).append(",\n")
                .append("            \"localY\": ").append(d.localY()).append(",\n")
                .append("            \"fullX\": ").append(d.fullSensorX()).append(",\n")
                .append("            \"fullY\": ").append(d.fullSensorY()).append(",\n")
                .append("            \"bitStart\": ").append(t.bitStart()).append(",\n")
                .append("            \"bitEnd\": ").append(t.bitEnd()).append(",\n")
                .append("            \"kBefore\": ").append(t.kBefore()).append(",\n")
                .append("            \"kAfter\": ").append(t.kAfter()).append(",\n")
                .append("            \"quotient\": ").append(t.quotient()).append(",\n")
                .append("            \"remainder\": ").append(t.remainder()).append(",\n")
                .append("            \"decodedUnsigned\": ").append(t.unsignedSymbol()).append(",\n")
                .append("            \"expectedUnsigned\": ").append(r.expectedUnsignedSymbol()).append(",\n")
                .append("            \"decodedResidual\": ").append(t.signedResidual()).append(",\n")
                .append("            \"expectedResidual\": ").append(r.expectedSignedResidual()).append(",\n")
                .append("            \"decoderPredictor\": ").append(t.predictor()).append(",\n")
                .append("            \"referencePredictor\": ").append(r.predictor()).append(",\n")
                .append("            \"reconstructed\": ").append(t.reconstructed()).append(",\n")
                .append("            \"referenceValue\": ").append(r.referenceValue()).append(",\n")
                .append("            \"west\": ").append(jsonNullable(r.west())).append(",\n")
                .append("            \"north\": ").append(jsonNullable(r.north())).append(",\n")
                .append("            \"northWest\": ").append(jsonNullable(r.northWest())).append(",\n")
                .append("            \"signedError\": ").append(d.signedError()).append("\n")
                .append("          }\n        }");
    }

    private static List<FixtureResult> sorted(List<FixtureResult> input) {
        Objects.requireNonNull(input, "input");
        List<FixtureResult> copy = new ArrayList<>(input);
        copy.sort(Comparator.comparing(FixtureResult::fixture));
        return List.copyOf(copy);
    }

    private static List<ReferenceGuidedFirstDivergenceAnalyzer.AnalysisResult> sortedStreams(
            List<ReferenceGuidedFirstDivergenceAnalyzer.AnalysisResult> input) {
        return input.stream()
                .sorted(Comparator.comparingInt(
                                (ReferenceGuidedFirstDivergenceAnalyzer.AnalysisResult r) ->
                                        r.mapping().tileSequence())
                        .thenComparingInt(r -> r.mapping().planeIndex())
                        .thenComparing(ReferenceGuidedFirstDivergenceAnalyzer.AnalysisResult
                                ::initializationMode))
                .toList();
    }

    private static void appendTsv(StringBuilder out, Object... values) {
        for (int index = 0; index < values.length; index++) {
            if (index > 0) out.append('\t');
            Object value = values[index];
            if (value != null) {
                out.append(value.toString().replace('\t', ' ').replace('\n', ' '));
            }
        }
        out.append('\n');
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.9f", value);
    }

    private static String nullable(Integer value) {
        return value == null ? "—" : value.toString();
    }

    private static String jsonNullable(Integer value) {
        return value == null ? "null" : value.toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
