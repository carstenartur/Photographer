package com.photographer.focusstack.cr3.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lightweight source-level dependency guards for the CR3 module split.
 *
 * <p>The current code still lives in one Maven module, so Maven cannot yet
 * enforce the desired dependency direction. These tests make accidental upward
 * package dependencies visible before the packages are physically extracted.</p>
 */
class Cr3PackageBoundaryTest {

    private static final String CR3 = "com.photographer.focusstack.cr3.";
    private static final Path MODULE_ROOT = Path.of(System.getProperty("basedir", "."))
            .toAbsolutePath()
            .normalize();
    private static final Path MAIN_SOURCES = MODULE_ROOT.resolve(Path.of("src", "main", "java"));

    @Test
    void productionSourcesMustNotDependOnResearchPackages() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path source : javaSources()) {
            String path = normalized(source);
            if (path.contains("/research/")) {
                continue;
            }
            findForbiddenImports(source, List.of(CR3 + "research."), violations);
        }
        assertThat(violations)
                .as("production CR3 sources must not import research code")
                .isEmpty();
    }

    @Test
    void packageLayersMustFollowThePlannedModuleDependencyDirection() throws IOException {
        List<LayerRule> rules = List.of(
                new LayerRule("/cr3/bmff/", List.of(
                        CR3 + "metadata.", CR3 + "raw.", CR3 + "processing.",
                        CR3 + "decode.", CR3 + "imageio.", CR3 + "cli.", CR3 + "research.")),
                new LayerRule("/cr3/container/", List.of(
                        CR3 + "metadata.", CR3 + "raw.", CR3 + "processing.",
                        CR3 + "decode.", CR3 + "imageio.", CR3 + "cli.", CR3 + "research.")),
                new LayerRule("/cr3/metadata/", List.of(
                        CR3 + "raw.", CR3 + "processing.", CR3 + "decode.",
                        CR3 + "imageio.", CR3 + "cli.", CR3 + "research.")),
                new LayerRule("/cr3/preview/", List.of(
                        CR3 + "raw.", CR3 + "processing.", CR3 + "decode.",
                        CR3 + "imageio.", CR3 + "cli.", CR3 + "research.")),
                new LayerRule("/cr3/raw/", List.of(
                        CR3 + "processing.", CR3 + "decode.", CR3 + "imageio.",
                        CR3 + "cli.", CR3 + "research.")),
                new LayerRule("/cr3/processing/", List.of(
                        CR3 + "decode.", CR3 + "imageio.", CR3 + "cli.", CR3 + "research.")),
                new LayerRule("/cr3/decode/", List.of(
                        CR3 + "imageio.", CR3 + "cli.", CR3 + "research."))
        );

        List<String> violations = new ArrayList<>();
        for (Path source : javaSources()) {
            String path = normalized(source);
            for (LayerRule rule : rules) {
                if (path.contains(rule.sourcePathFragment())) {
                    findForbiddenImports(source, rule.forbiddenImports(), violations);
                }
            }
        }

        assertThat(violations)
                .as("CR3 packages must follow docs/cr3-package-ownership.md")
                .isEmpty();
    }

    private static List<Path> javaSources() throws IOException {
        assertThat(MAIN_SOURCES).as("core main source directory").isDirectory();
        try (var stream = Files.walk(MAIN_SOURCES)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
        }
    }

    private static void findForbiddenImports(Path source, List<String> forbidden,
                                             List<String> violations) throws IOException {
        List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
        for (int index = 0; index < lines.size(); index++) {
            String importedType = importedType(lines.get(index));
            if (importedType == null) {
                continue;
            }
            for (String prefix : forbidden) {
                if (importedType.startsWith(prefix)) {
                    violations.add(normalized(source) + ":" + (index + 1)
                            + " -> " + lines.get(index).trim());
                }
            }
        }
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

    private record LayerRule(String sourcePathFragment, List<String> forbiddenImports) {
    }
}
