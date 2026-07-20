package com.photographer.focusstack.cr3.research;

import java.util.List;

final class KAdaptationTraceReport {
    enum Classification {
        K_STATE_BEFORE_DIFFERENT_BITS, RESET_STATE, SYMBOL_INTERPRETATION,
        BIT_OR_STRUCTURE_POSITION, OTHER_STATE, NO_DIVERGENCE_WITHIN_BOUND
    }

    record Pair(int index, CrxNormativeLosslessCandidate.SymbolTrace hybrid,
                CrxNormativeLosslessCandidate.SymbolTrace bracket) {}
    record Result(String fixture, boolean found, Classification classification,
                  int tile, int plane, int divergenceIndex, List<Pair> context,
                  boolean hybridTruncated, boolean bracketTruncated) {}

    private KAdaptationTraceReport() {}

    static String markdown(List<Result> results) {
        StringBuilder out = new StringBuilder("# D7 First K / Bit-Position Divergence\n\n")
                .append("Both D6 decoders are unchanged; only bounded trace data is recorded.\n\n");
        for (Result result : results) {
            out.append("## `").append(result.fixture()).append("`\n\n")
                    .append("- found: ").append(result.found()).append('\n')
                    .append("- classification: **").append(result.classification()).append("**\n")
                    .append("- tile / plane: ").append(result.tile()).append(" / ").append(result.plane()).append('\n')
                    .append("- divergence index: ").append(result.divergenceIndex()).append('\n')
                    .append("- trace truncated HYBRID/SYMBOL_BRACKET: ")
                    .append(result.hybridTruncated()).append(" / ").append(result.bracketTruncated()).append("\n\n")
                    .append("| i | variant | x | y | start byte:bit | end byte:bit | K0 | q | r | S | diff | pred | value | K1 |\n")
                    .append("|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
            for (Pair pair : result.context()) {
                row(out, pair.index(), "HYBRID", pair.hybrid());
                row(out, pair.index(), "SYMBOL_BRACKET", pair.bracket());
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static void row(StringBuilder out, int index, String variant,
                            CrxNormativeLosslessCandidate.SymbolTrace t) {
        out.append("| ").append(index).append(" | ").append(variant)
                .append(" | ").append(t.x()).append(" | ").append(t.y())
                .append(" | ").append(pos(t.bitStart())).append(" | ").append(pos(t.bitEnd()))
                .append(" | ").append(t.kBefore()).append(" | ").append(t.quotient())
                .append(" | ").append(t.remainder()).append(" | ").append(t.unsignedSymbol())
                .append(" | ").append(t.signedResidual()).append(" | ").append(t.predictor())
                .append(" | ").append(t.reconstructed()).append(" | ").append(t.kAfter()).append(" |\n");
    }

    private static String pos(long bit) { return (bit / 8) + ":" + (bit % 8); }

    static String json(List<Result> results) {
        StringBuilder out = new StringBuilder("{\n  \"results\": [\n");
        for (int i = 0; i < results.size(); i++) {
            if (i > 0) out.append(",\n");
            Result r = results.get(i);
            out.append("    {\"fixture\":\"").append(escape(r.fixture()))
                    .append("\",\"found\":").append(r.found())
                    .append(",\"classification\":\"").append(r.classification())
                    .append("\",\"tile\":").append(r.tile()).append(",\"plane\":").append(r.plane())
                    .append(",\"divergenceIndex\":").append(r.divergenceIndex()).append(",\"context\":[");
            for (int j = 0; j < r.context().size(); j++) {
                if (j > 0) out.append(',');
                Pair p = r.context().get(j);
                out.append("{\"index\":").append(p.index()).append(",\"hybrid\":");
                traceJson(out, p.hybrid());
                out.append(",\"symbolBracket\":");
                traceJson(out, p.bracket());
                out.append('}');
            }
            out.append("]}");
        }
        return out.append("\n  ]\n}\n").toString();
    }

    private static void traceJson(StringBuilder out, CrxNormativeLosslessCandidate.SymbolTrace t) {
        out.append("{\"ordinal\":").append(t.ordinal()).append(",\"x\":").append(t.x()).append(",\"y\":").append(t.y())
                .append(",\"absoluteBitStart\":").append(t.bitStart()).append(",\"byteStart\":").append(t.bitStart() / 8)
                .append(",\"bitStartInByte\":").append(t.bitStart() % 8).append(",\"absoluteBitEnd\":").append(t.bitEnd())
                .append(",\"byteEnd\":").append(t.bitEnd() / 8).append(",\"bitEndInByte\":").append(t.bitEnd() % 8)
                .append(",\"kBefore\":").append(t.kBefore()).append(",\"q\":").append(t.quotient())
                .append(",\"r\":").append(t.remainder()).append(",\"symbol\":").append(t.unsignedSymbol())
                .append(",\"signed\":").append(t.signedResidual()).append(",\"predictor\":").append(t.predictor())
                .append(",\"value\":").append(t.reconstructed()).append(",\"kAfter\":").append(t.kAfter()).append('}');
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
