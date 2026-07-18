package com.photographer.focusstack.cr3.raw.codec;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the RAW codec against dependencies on post-codec and adapter layers. */
class Cr3CodecModuleBoundaryTest {

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

    private static final List<String> FORBIDDEN_IMPORT_PREFIXES = List.of(
            "com.photographer.focusstack.cr3.processing.",
            "com.photographer.focusstack.cr3.decode.",
            "com.photographer.focusstack.cr3.imageio.",
            "com.photographer.focusstack.cr3.cli.",
            "com.photographer.focusstack.cr3.research."
    );

    @Test
    void codecMustRemainIndependentOfProcessingFacadeAndAdapters() throws IOException {
        List<String> violations = new ArrayList<>();

        try (var stream = Files.walk(MAIN_SOURCES)) {
            for (Path source : stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList()) {
                List<String> lines = Files.readAllLines(source);
                for (int index = 0; index < lines.size(); index++) {
                    String line = lines.get(index).trim();
                    if (!line.startsWith("import ")) {
                        continue;
                    }
                    for (String forbidden : FORBIDDEN_IMPORT_PREFIXES) {
                        if (line.startsWith("import " + forbidden)) {
                            violations.add(normalized(source) + ":" + (index + 1) + " -> " + line);
                        }
                    }
                }
            }
        }

        assertThat(violations)
                .as("canon-cr3-codec must not depend on processing, facade, adapters or research")
                .isEmpty();
    }

    private static String normalized(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }
}
