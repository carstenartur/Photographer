package com.photographer.focusstack.cr3.research;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads the durable corrected D8 outcome matrix without recomputing metrics. */
final class D8OutcomeTable {
    static final Path DEFAULT_PATH = Path.of(System.getProperty(
            "cr3.d8.outcomes",
            "../docs/cr3-results/2026-07-20-D8-zero-contracted-k-outcomes.tsv"));

    enum OutcomeClass {
        COMPLETE_IMPROVEMENT,
        COMPLETE_MIXED,
        COMPLETE_GLOBAL_REGRESSION,
        COMPLETION_REGRESSION
    }

    record Outcome(String fixture, OutcomeClass outcomeClass,
                   double hybridMae, double d8Mae, double deltaMae,
                   double hybridObMae, double d8ObMae, double deltaObMae,
                   double hybridBoundary, double d8Boundary, double deltaBoundary,
                   double hybridCompletion, double d8Completion,
                   double deltaCompletion,
                   boolean hybridExhausted, boolean d8Exhausted) {
    }

    private D8OutcomeTable() {
    }

    static Map<String, Outcome> load(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path);
        if (lines.isEmpty()) {
            throw new IOException("Empty D8 outcome table: " + path);
        }
        String expectedHeader = "fixture\toutcomeClass\thybridMae\td8Mae\tdeltaMae"
                + "\thybridObMae\td8ObMae\tdeltaObMae"
                + "\thybridBoundary\td8Boundary\tdeltaBoundary"
                + "\thybridCompletion\td8Completion\tdeltaCompletion"
                + "\thybridExhausted\td8Exhausted";
        if (!expectedHeader.equals(lines.getFirst())) {
            throw new IOException("Unexpected D8 outcome header in " + path);
        }

        Map<String, Outcome> outcomes = new LinkedHashMap<>();
        for (int lineNumber = 1; lineNumber < lines.size(); lineNumber++) {
            String line = lines.get(lineNumber);
            if (line.isBlank()) {
                continue;
            }
            String[] fields = line.split("\\t", -1);
            if (fields.length != 16) {
                throw new IOException("Expected 16 fields at line "
                        + (lineNumber + 1) + " in " + path);
            }
            Outcome outcome;
            try {
                outcome = new Outcome(
                        fields[0],
                        OutcomeClass.valueOf(fields[1]),
                        number(fields[2]), number(fields[3]), number(fields[4]),
                        number(fields[5]), number(fields[6]), number(fields[7]),
                        number(fields[8]), number(fields[9]), number(fields[10]),
                        number(fields[11]), number(fields[12]), number(fields[13]),
                        Boolean.parseBoolean(fields[14]),
                        Boolean.parseBoolean(fields[15]));
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid D8 outcome at line "
                        + (lineNumber + 1) + " in " + path, exception);
            }
            if (outcomes.putIfAbsent(outcome.fixture(), outcome) != null) {
                throw new IOException("Duplicate D8 outcome for " + outcome.fixture());
            }
        }
        return Map.copyOf(outcomes);
    }

    private static double number(String value) {
        return value.isBlank() ? Double.NaN : Double.parseDouble(value);
    }
}
