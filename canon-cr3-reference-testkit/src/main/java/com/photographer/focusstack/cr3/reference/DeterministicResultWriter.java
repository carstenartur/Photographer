package com.photographer.focusstack.cr3.reference;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Writes the conventional Markdown/TSV/JSON result bundle used by active CR3
 * reference experiments with stable UTF-8 and Unix-newline semantics.
 *
 * <p>This utility intentionally does not build or reorder report structures.
 * Callers retain ownership of numerical definitions and semantic ordering; this
 * class only centralizes deterministic persistence shared by multiple reports.</p>
 */
public final class DeterministicResultWriter {

    public static final String MARKDOWN_FILE = "results.md";
    public static final String TSV_FILE = "results.tsv";
    public static final String JSON_FILE = "results.json";

    private DeterministicResultWriter() {
    }

    public static ResultBundle writeBundle(
            Path outputDirectory,
            String markdown,
            String tsv,
            String json) throws IOException {
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        Path normalizedDirectory = outputDirectory.toAbsolutePath().normalize();
        Files.createDirectories(normalizedDirectory);
        Path markdownPath = normalizedDirectory.resolve(MARKDOWN_FILE);
        Path tsvPath = normalizedDirectory.resolve(TSV_FILE);
        Path jsonPath = normalizedDirectory.resolve(JSON_FILE);
        write(markdownPath, markdown);
        write(tsvPath, tsv);
        write(jsonPath, json);
        return new ResultBundle(markdownPath, tsvPath, jsonPath);
    }

    public static void write(Path path, String content) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(content, "content");
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(
                path,
                normalize(content),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    /** Converts CRLF/CR to LF and writes exactly one trailing newline. */
    public static String normalize(String content) {
        Objects.requireNonNull(content, "content");
        String unix = content.replace("\r\n", "\n").replace('\r', '\n');
        int end = unix.length();
        while (end > 0 && unix.charAt(end - 1) == '\n') {
            end--;
        }
        return unix.substring(0, end) + "\n";
    }

    public record ResultBundle(Path markdown, Path tsv, Path json) {
        public ResultBundle {
            Objects.requireNonNull(markdown, "markdown");
            Objects.requireNonNull(tsv, "tsv");
            Objects.requireNonNull(json, "json");
        }
    }
}
