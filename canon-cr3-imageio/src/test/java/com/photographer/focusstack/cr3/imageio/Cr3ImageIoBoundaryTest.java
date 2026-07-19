package com.photographer.focusstack.cr3.imageio;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the ImageIO adapter boundary before the CR3 Maven module split. */
class Cr3ImageIoBoundaryTest {

    private static final Path MODULE_ROOT = Path.of(System.getProperty("basedir", "."))
            .toAbsolutePath()
            .normalize();
    private static final Path MAIN_SOURCES = MODULE_ROOT.resolve(Path.of("src", "main", "java"));

    @Test
    void imageIoProductionCodeMustNotDependOnCliOrResearch() throws IOException {
        assertThat(MAIN_SOURCES).as("ImageIO main source directory").isDirectory();

        List<String> violations = new ArrayList<>();
        try (var stream = Files.walk(MAIN_SOURCES)) {
            for (Path source : stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList()) {
                List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
                for (int index = 0; index < lines.size(); index++) {
                    String importedType = importedType(lines.get(index));
                    if (importedType != null
                            && (importedType.startsWith("com.photographer.focusstack.cr3.cli.")
                            || importedType.startsWith("com.photographer.focusstack.cr3.research."))) {
                        violations.add(normalized(source) + ":" + (index + 1)
                                + " -> " + lines.get(index).trim());
                    }
                }
            }
        }

        assertThat(violations)
                .as("canon-cr3-imageio must remain an adapter without CLI/research dependencies")
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
