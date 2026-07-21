package com.photographer.focusstack.cr3.reference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeterministicResultWriterTest {

    @Test
    void writesConventionalBundleAsUtf8WithOneUnixNewline(@TempDir Path temp)
            throws Exception {
        String umlaut = "fixture\täöü\r\nvalue\t1\r\n\r\n";

        DeterministicResultWriter.ResultBundle bundle =
                DeterministicResultWriter.writeBundle(
                        temp.resolve("nested"),
                        "# Bericht\r\n\r\n",
                        umlaut,
                        "{\r\n  \"value\": \"äöü\"\r\n}\r\n\r\n");

        assertThat(bundle.markdown().getFileName().toString())
                .isEqualTo(DeterministicResultWriter.MARKDOWN_FILE);
        assertThat(bundle.tsv().getFileName().toString())
                .isEqualTo(DeterministicResultWriter.TSV_FILE);
        assertThat(bundle.json().getFileName().toString())
                .isEqualTo(DeterministicResultWriter.JSON_FILE);
        assertThat(Files.readString(bundle.markdown(), StandardCharsets.UTF_8))
                .isEqualTo("# Bericht\n");
        assertThat(Files.readString(bundle.tsv(), StandardCharsets.UTF_8))
                .isEqualTo("fixture\täöü\nvalue\t1\n");
        assertThat(Files.readString(bundle.json(), StandardCharsets.UTF_8))
                .isEqualTo("{\n  \"value\": \"äöü\"\n}\n");
    }

    @Test
    void overwritesExistingFilesDeterministically(@TempDir Path temp)
            throws Exception {
        DeterministicResultWriter.writeBundle(temp, "old", "old", "old");
        DeterministicResultWriter.writeBundle(temp, "new\n\n", "new", "new");

        assertThat(Files.readString(temp.resolve("results.md")))
                .isEqualTo("new\n");
        assertThat(Files.readString(temp.resolve("results.tsv")))
                .isEqualTo("new\n");
        assertThat(Files.readString(temp.resolve("results.json")))
                .isEqualTo("new\n");
    }

    @Test
    void normalizesMixedLineEndingsAndEmptyContent() {
        assertThat(DeterministicResultWriter.normalize("a\rb\r\nc\n\n"))
                .isEqualTo("a\nb\nc\n");
        assertThat(DeterministicResultWriter.normalize(""))
                .isEqualTo("\n");
    }

    @Test
    void rejectsNullInputs(@TempDir Path temp) {
        assertThatThrownBy(() -> DeterministicResultWriter.writeBundle(
                temp, null, "", ""))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> DeterministicResultWriter.write(null, ""))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> DeterministicResultWriter.normalize(null))
                .isInstanceOf(NullPointerException.class);
    }
}
