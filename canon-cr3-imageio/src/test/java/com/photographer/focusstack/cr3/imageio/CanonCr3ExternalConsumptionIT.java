package com.photographer.focusstack.cr3.imageio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class CanonCr3ExternalConsumptionIT {

    @Test
    @DisplayName("packaged ImageIO plugin works from a clean JVM classpath")
    void packagedJarSupportsExternalConsumption() throws Exception {
        Path testClassesDir = locationOf(CanonCr3ExternalConsumptionIT.class);
        Path targetDir = testClassesDir.getParent();
        Path moduleDir = targetDir.getParent();
        Path jarFile = findPackagedJar(targetDir);
        Path classesDir = targetDir.resolve("classes").toAbsolutePath().normalize();
        Path sampleFile = resourcePath("/samples/sample.cr3");
        Path thumbnailReference = resourcePath("/samples/sample-thumbnail.jpg");

        ProcessBuilder processBuilder = new ProcessBuilder(
                javaExecutable(),
                "-cp",
                childClasspath(classesDir, jarFile),
                ExternalConsumptionProbe.class.getName(),
                jarFile.toString(),
                sampleFile.toString(),
                thumbnailReference.toString()
        );
        processBuilder.directory(moduleDir.toFile());
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();
        ExecutorService outputReaderExecutor = Executors.newSingleThreadExecutor();
        String output;
        boolean exited;
        try {
            Future<String> outputFuture = outputReaderExecutor.submit(
                    () -> new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
            );
            exited = process.waitFor(90, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
            output = outputFuture.get(10, TimeUnit.SECONDS);
        } finally {
            outputReaderExecutor.shutdownNow();
        }

        assertThat(exited)
                .withFailMessage("External consumption probe timed out after 90 seconds.%nOutput so far:%n%s", output)
                .isTrue();

        int exitCode = process.exitValue();

        assertThat(exitCode)
                .withFailMessage("External consumption probe failed with output:%n%s", output)
                .isZero();
    }

    private static Path findPackagedJar(Path targetDir) throws Exception {
        try (Stream<Path> stream = Files.list(targetDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("canon-cr3-imageio-"))
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .filter(path -> !path.getFileName().toString().contains("-tests"))
                    .filter(path -> !path.getFileName().toString().contains("-sources"))
                    .filter(path -> !path.getFileName().toString().contains("-javadoc"))
                    .filter(path -> !path.getFileName().toString().startsWith("original-"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Packaged canon-cr3-imageio JAR not found in " + targetDir));
        }
    }

    private static String childClasspath(Path classesDir, Path jarFile) {
        String separator = System.getProperty("path.separator");
        String currentClasspath = System.getProperty("java.class.path", "");
        return Stream.concat(
                        Stream.of(jarFile.toString()),
                        Arrays.stream(currentClasspath.split(java.util.regex.Pattern.quote(separator)))
                                .filter(entry -> !entry.isBlank())
                                .filter(entry -> {
                                    Path normalized = Path.of(entry).toAbsolutePath().normalize();
                                    return !normalized.equals(classesDir);
                                })
                )
                .distinct()
                .collect(Collectors.joining(separator));
    }

    private static Path locationOf(Class<?> type) throws URISyntaxException {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toAbsolutePath()
                .normalize();
    }

    private static Path resourcePath(String resourceName) throws URISyntaxException {
        return Path.of(CanonCr3ExternalConsumptionIT.class.getResource(resourceName).toURI())
                .toAbsolutePath()
                .normalize();
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }
}
