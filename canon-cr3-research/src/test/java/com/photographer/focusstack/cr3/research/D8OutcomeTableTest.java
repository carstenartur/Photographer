package com.photographer.focusstack.cr3.research;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

class D8OutcomeTableTest {
    @Test
    void loadsExactDurableFixedPanel() throws Exception {
        Map<String, D8OutcomeTable.Outcome> outcomes =
                D8OutcomeTable.load(D8OutcomeTable.DEFAULT_PATH);

        assertThat(outcomes).hasSize(KAdaptationFixtureSet.PANEL.size());
        assertThat(outcomes.keySet())
                .containsExactlyInAnyOrderElementsOf(KAdaptationFixtureSet.PANEL);
        assertThat(outcomes.get("eosr-iso-low.cr3").outcomeClass())
                .isEqualTo(D8OutcomeTable.OutcomeClass.COMPLETE_IMPROVEMENT);
        assertThat(outcomes.get("sample-iso-high.cr3").outcomeClass())
                .isEqualTo(D8OutcomeTable.OutcomeClass.COMPLETION_REGRESSION);
    }

    @Test
    void rejectsUnexpectedHeader(@TempDir Path temp) throws Exception {
        Path table = temp.resolve("outcomes.tsv");
        Files.writeString(table, "wrong\theader\n");

        assertThatIOException()
                .isThrownBy(() -> D8OutcomeTable.load(table))
                .withMessageContaining("Unexpected D8 outcome header");
    }

    @Test
    void rejectsDuplicateFixture(@TempDir Path temp) throws Exception {
        Path table = temp.resolve("outcomes.tsv");
        String header = "fixture\toutcomeClass\thybridMae\td8Mae\tdeltaMae"
                + "\thybridObMae\td8ObMae\tdeltaObMae"
                + "\thybridBoundary\td8Boundary\tdeltaBoundary"
                + "\thybridCompletion\td8Completion\tdeltaCompletion"
                + "\thybridExhausted\td8Exhausted\n";
        String row = "a.cr3\tCOMPLETE_IMPROVEMENT\t1\t0\t-1"
                + "\t1\t0\t-1\t1\t0\t-1\t1\t1\t0\tfalse\tfalse\n";
        Files.writeString(table, header + row + row);

        assertThatIOException()
                .isThrownBy(() -> D8OutcomeTable.load(table))
                .withMessageContaining("Duplicate D8 outcome");
    }
}
