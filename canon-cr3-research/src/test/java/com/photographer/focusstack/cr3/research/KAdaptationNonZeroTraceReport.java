package com.photographer.focusstack.cr3.research;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

final class KAdaptationNonZeroTraceReport {
    enum Classification {
        HYBRID_ACCUMULATOR_DECREASE_VS_D8_HOLD,
        HYBRID_HOLD_VS_D8_INCREASE,
        HYBRID_INCREASE_VS_D8_HOLD_OR_DECREASE,
        SAME_K_DIFFERENT_SYMBOL_OR_BITS,
        RESET_OR_STRUCTURE_SCOPE,
        EXHAUSTION_BEFORE_NON_ZERO_DIVERGENCE,
        NO_DIVERGENCE_WITHIN_BOUND,
        OTHER_K_DECISION
    }

    record Pair(int index,
                KAdaptationStateTracer.Trace hybrid,
                KAdaptationStateTracer.Trace d8) {
    }

    record Result(String fixture, boolean found,
                  Classification classification,
                  int tile, int plane, int divergenceIndex,
                  List<Pair> context,
                  boolean hybridTruncated, boolean d8Truncated,
                  boolean hybridExhausted, boolean d8Exhausted) {
    }

    private KAdaptationNonZeroTraceReport() {
    }

    static String selectedNextStep(List<Result> results) {
        Map<Classification, Integer> counts = new EnumMap<>(Classification.class);
        for (Result result : results) {
            counts.merge(result.classification(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .filter(entry -> entry.getValue() >= 2)
                .filter(entry -> entry.getKey()
                        != Classification.NO_DIVERGENCE_WITHIN_BOUND)
                .filter(entry -> entry.getKey()
                        != Classification.EXHAUSTION_BEFORE_NON_ZERO_DIVERGENCE)
                .filter(entry -> entry.getKey()
                        != Classification.OTHER_K_DECISION)
                .sorted(Map.Entry.<Classification, Integer>comparingByValue().reversed()
                        .thenComparing(entry -> entry.getKey().name()))
                .map(entry -> entry.getKey().name())
                .findFirst()
                .orElse("NO_COMMON_CAUSE");
    }

    static String markdown(List<Result> results) {
        String next = selectedNextStep(results);
        StringBuilder out = new StringBuilder(
                "# D9 First Non-zero K Divergence\n\n")
                .append("HYBRID and D8 decoder semantics are unchanged; both are observed ")
                .append("through one shared test-scope parser.\n\n")
                .append("## Common next cause\n\n")
                .append("`").append(next).append("`\n\n");
        for (Result result : results) {
            out.append("## `").append(result.fixture()).append("`\n\n")
                    .append("- found: ").append(result.found()).append('\n')
                    .append("- classification: **")
                    .append(result.classification()).append("**\n")
                    .append("- tile / plane: ")
                    .append(result.tile()).append(" / ")
                    .append(result.plane()).append('\n')
                    .append("- divergence index: ")
                    .append(result.divergenceIndex()).append('\n')
                    .append("- trace truncated HYBRID/D8: ")
                    .append(result.hybridTruncated()).append(" / ")
                    .append(result.d8Truncated()).append('\n')
                    .append("- exhausted HYBRID/D8: ")
                    .append(result.hybridExhausted()).append(" / ")
                    .append(result.d8Exhausted()).append("\n\n")
                    .append("| i | rule | x | y | start | end | K0 | q | r | S | diff | pred | value | K1 | zero-run | accum0 | accum1 | count0 | count1 | K* | Kmin | Kmax | decision |\n")
                    .append("|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|\n");
            for (Pair pair : result.context()) {
                row(out, pair.index(), "HYBRID", pair.hybrid());
                row(out, pair.index(), "D8", pair.d8());
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static void row(StringBuilder out, int index, String rule,
                            KAdaptationStateTracer.Trace trace) {
        out.append("| ").append(index).append(" | ").append(rule)
                .append(" | ").append(trace.x())
                .append(" | ").append(trace.y())
                .append(" | ").append(position(trace.bitStart()))
                .append(" | ").append(position(trace.bitEnd()))
                .append(" | ").append(trace.kBefore())
                .append(" | ").append(trace.quotient())
                .append(" | ").append(trace.remainder())
                .append(" | ").append(trace.unsignedSymbol())
                .append(" | ").append(trace.signedResidual())
                .append(" | ").append(trace.predictor())
                .append(" | ").append(trace.reconstructed())
                .append(" | ").append(trace.kAfter())
                .append(" | ").append(trace.zeroRunBefore())
                .append(" | ").append(trace.accumulatorBefore())
                .append(" | ").append(trace.accumulatorAfter())
                .append(" | ").append(trace.sampleCountBefore())
                .append(" | ").append(trace.sampleCountAfter())
                .append(" | ").append(trace.kStar())
                .append(" | ").append(trace.kMin())
                .append(" | ").append(trace.kMax())
                .append(" | ").append(trace.decision())
                .append(" |\n");
    }

    private static String position(long bit) {
        return (bit / 8) + ":" + (bit % 8);
    }

    static String json(List<Result> results) {
        StringBuilder out = new StringBuilder("{\n  \"selectedNextCause\": \"")
                .append(selectedNextStep(results))
                .append("\",\n  \"results\": [\n");
        for (int i = 0; i < results.size(); i++) {
            if (i > 0) {
                out.append(",\n");
            }
            Result result = results.get(i);
            out.append("    {\"fixture\":\"")
                    .append(escape(result.fixture()))
                    .append("\",\"found\":").append(result.found())
                    .append(",\"classification\":\"")
                    .append(result.classification())
                    .append("\",\"tile\":").append(result.tile())
                    .append(",\"plane\":").append(result.plane())
                    .append(",\"divergenceIndex\":")
                    .append(result.divergenceIndex())
                    .append(",\"hybridTruncated\":")
                    .append(result.hybridTruncated())
                    .append(",\"d8Truncated\":")
                    .append(result.d8Truncated())
                    .append(",\"hybridExhausted\":")
                    .append(result.hybridExhausted())
                    .append(",\"d8Exhausted\":")
                    .append(result.d8Exhausted())
                    .append(",\"context\":[");
            for (int j = 0; j < result.context().size(); j++) {
                if (j > 0) {
                    out.append(',');
                }
                Pair pair = result.context().get(j);
                out.append("{\"index\":").append(pair.index())
                        .append(",\"hybrid\":");
                traceJson(out, pair.hybrid());
                out.append(",\"d8\":");
                traceJson(out, pair.d8());
                out.append('}');
            }
            out.append("]}");
        }
        return out.append("\n  ]\n}\n").toString();
    }

    private static void traceJson(StringBuilder out,
                                  KAdaptationStateTracer.Trace trace) {
        out.append("{\"ordinal\":").append(trace.ordinal())
                .append(",\"x\":").append(trace.x())
                .append(",\"y\":").append(trace.y())
                .append(",\"absoluteBitStart\":")
                .append(trace.bitStart())
                .append(",\"byteStart\":").append(trace.bitStart() / 8)
                .append(",\"bitStartInByte\":")
                .append(trace.bitStart() % 8)
                .append(",\"absoluteBitEnd\":")
                .append(trace.bitEnd())
                .append(",\"byteEnd\":").append(trace.bitEnd() / 8)
                .append(",\"bitEndInByte\":")
                .append(trace.bitEnd() % 8)
                .append(",\"kBefore\":").append(trace.kBefore())
                .append(",\"kAfter\":").append(trace.kAfter())
                .append(",\"q\":").append(trace.quotient())
                .append(",\"r\":").append(trace.remainder())
                .append(",\"symbol\":").append(trace.unsignedSymbol())
                .append(",\"signed\":").append(trace.signedResidual())
                .append(",\"predictor\":").append(trace.predictor())
                .append(",\"value\":").append(trace.reconstructed())
                .append(",\"zeroRunBefore\":")
                .append(trace.zeroRunBefore())
                .append(",\"accumulatorBefore\":")
                .append(trace.accumulatorBefore())
                .append(",\"accumulatorAfter\":")
                .append(trace.accumulatorAfter())
                .append(",\"sampleCountBefore\":")
                .append(trace.sampleCountBefore())
                .append(",\"sampleCountAfter\":")
                .append(trace.sampleCountAfter())
                .append(",\"kStar\":").append(trace.kStar())
                .append(",\"kMin\":").append(trace.kMin())
                .append(",\"kMax\":").append(trace.kMax())
                .append(",\"decision\":\"")
                .append(trace.decision()).append("\"}");
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
