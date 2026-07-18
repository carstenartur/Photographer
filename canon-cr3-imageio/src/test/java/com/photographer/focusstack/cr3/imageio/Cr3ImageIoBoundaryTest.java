package com.photographer.focusstack.cr3.imageio;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the ImageIO adapter boundary before the CR3 Maven module split. */
class Cr3ImageIoBoundaryTest {

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

    @Test
    void imageIoProductionCodeMustNotDependOnCliOrResearch() throws IOException {
        List<String> violations = new ArrayList<>();
        try (var stream = Files.walk(MAIN_SOURCES)) {
            for (Path source : stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList()) {
                List<String> lines = Files.readAllLines(source);
                for (int index = 0; index < lines.size(); index++) {
                    String line = lines.get(index).trim();
                    if (line.startsWith("import com.photographer.focusstack.cr3.cli.")
                            || line.startsWith("import com.photographer.focusstack.cr3.research.")) {
                        violations.add(normalized(source) + ":" + (index + 1) + " -> " + line);
                    }
                }
            }
        }

        assertThat(violations)
                .as("canon-cr3-imageio must remain an adapter without CLI/research dependencies")
                .isEmpty();
    }

    private static String normalized(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }
}
