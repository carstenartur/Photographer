package com.photographer.focusstack.cr3.processing;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards post-codec processing against container internals, facades, adapters and research. */
class Cr3ProcessingModuleBoundaryTest {

    private static final Path MODULE_ROOT = Path.of(System.getProperty("basedir", "."))
            .toAbsolutePath()
            .normalize();
    private static final Path MAIN_SOURCES = MODULE_ROOT.resolve(Path.of("src", "main", "java"));

    private static final List<String> FORBIDDEN_IMPORT_PREFIXES = List.of(
            "com.photographer.focusstack.cr3.bmff.",
            "com.photographer.focusstack.cr3.container.",
            "com.photographer.focusstack.cr3.decode.",
            "com.photographer.focusstack.cr3.imageio.",
            "com.photographer.focusstack.cr3.cli.",
            "com.photographer.focusstack.cr3.research."
    );

    @Test
    void processingMustRemainIndependentOfContainerInternalsFacadeAdaptersAndResearch() throws IOException {
        assertThat(MAIN_SOURCES).as("processing main source directory").isDirectory();

        List<String> violations = new ArrayList<>();
        try (var stream = Files.walk(MAIN_SOURCES)) {
            for (Path source : stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList()) {
                List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
                for (int index = 0; index < lines.size(); index++) {
                    String importedType = importedType(lines.get(index));
                    if (importedType == null) {
                        continue;
                    }
                    for (String forbidden : FORBIDDEN_IMPORT_PREFIXES) {
                        if (importedType.startsWith(forbidden)) {
                            violations.add(normalized(source) + ":" + (index + 1)
                                    + " -> " + lines.get(index).trim());
                        }
                    }
                }
            }
        }

        assertThat(violations)
                .as("canon-cr3-processing must not depend on container internals, facades, adapters or research")
                .isEmpty();
    }

    private static String importedType(String sourceLine) {
        String line = sourceLine.trim();
        if (line.startsWith("import static ")) {
            return line.substring("import static ".length());
        }
        if (line.startsWith("import ")) {
            return line.substring("import ".length());
        }
        return null;
    }

    private static String normalized(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }
}
