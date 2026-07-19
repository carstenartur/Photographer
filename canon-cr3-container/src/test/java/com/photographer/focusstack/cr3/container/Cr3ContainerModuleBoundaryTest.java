package com.photographer.focusstack.cr3.container;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards {@code canon-cr3-container} as the lowest CR3 production layer.
 *
 * <p>The physical Maven split makes most dependency inversions impossible, but
 * this source-level check also prevents higher-layer CR3 packages from leaking
 * into the module through future dependency additions.</p>
 */
class Cr3ContainerModuleBoundaryTest {

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

    private static final List<String> FORBIDDEN_IMPORT_PREFIXES = List.of(
            "com.photographer.focusstack.cr3.metadata.",
            "com.photographer.focusstack.cr3.raw.",
            "com.photographer.focusstack.cr3.processing.",
            "com.photographer.focusstack.cr3.decode.",
            "com.photographer.focusstack.cr3.imageio.",
            "com.photographer.focusstack.cr3.cli.",
            "com.photographer.focusstack.cr3.research."
    );

    @Test
    void containerMustRemainIndependentOfHigherCr3Layers() throws IOException {
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
                .as("canon-cr3-container must remain the lowest CR3 production layer")
                .isEmpty();
    }

    private static String normalized(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }
}
