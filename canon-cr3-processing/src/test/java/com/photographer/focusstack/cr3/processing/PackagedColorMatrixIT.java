package com.photographer.focusstack.cr3.processing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.DataInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the shipped artifact: exploded reactor classes do not activate MR-JAR entries. */
class PackagedColorMatrixIT {
    private static final String PACKAGE = "com/photographer/focusstack/cr3/processing/";

    @TempDir
    Path temporary;

    @Test
    void packagedArtifactRetainsJava21BaseAndOptionalJava25Backend() throws Exception {
        Path artifact = Path.of(System.getProperty("cr3.processing.jar")).toAbsolutePath();
        try (JarFile jar = new JarFile(artifact.toFile())) {
            assertEquals("true", jar.getManifest().getMainAttributes().getValue("Multi-Release"));
            assertClassVersion(jar, PACKAGE + "ColorMatrixProcessor.class", 65);
            assertClassVersion(jar, "META-INF/versions/25/" + PACKAGE + "ColorMatrixVectors.class", 69);
        }

        runConsumer(artifact, false);
        runConsumer(artifact, true);
    }

    private static void assertClassVersion(JarFile jar, String name, int major) throws Exception {
        var entry = jar.getJarEntry(name);
        assertNotNull(entry, "Missing packaged implementation: " + name);
        try (var input = new DataInputStream(jar.getInputStream(entry))) {
            assertEquals(0xcafebabe, input.readInt());
            input.readUnsignedShort();
            assertEquals(major, input.readUnsignedShort(), name);
        }
    }

    private void runConsumer(Path artifact, boolean vectors) throws Exception {
        Path exploded = Path.of(System.getProperty("cr3.processing.classes")).toAbsolutePath().normalize();
        String dependencies = Arrays.stream(System.getProperty("java.class.path")
                        .split(Pattern.quote(File.pathSeparator)))
                .filter(element -> !Path.of(element).toAbsolutePath().normalize().startsWith(exploded))
                .collect(Collectors.joining(File.pathSeparator));
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Xmx128m", "-Djava.awt.headless=true"));
        if (vectors) {
            command.add("--add-modules=jdk.incubator.vector");
        }
        command.addAll(List.of("-cp", artifact + File.pathSeparator + dependencies,
                Probe.class.getName(), artifact.toString(), Boolean.toString(vectors)));
        Path output = temporary.resolve(vectors ? "vector.txt" : "scalar.txt");
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(output.toFile());
        for (String variable : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) {
            builder.environment().remove(variable);
        }
        Process process = builder.start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        assertTrue(finished, "Packaged consumer timed out");
        assertEquals(0, process.exitValue(), () -> read(output));
    }

    private static String read(Path output) {
        try {
            return Files.readString(output);
        } catch (Exception exception) {
            return exception.toString();
        }
    }

    public static class Probe {
        public static void main(String[] args) throws Exception {
            Path loaded = Path.of(ColorMatrixProcessor.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            if (!loaded.equals(Path.of(args[0]))) {
                throw new AssertionError("Consumer did not load the packaged processing artifact: " + loaded);
            }
            boolean expected = false;
            if (Boolean.parseBoolean(args[1]) && Runtime.version().feature() == 25) {
                Class<?> vector = Class.forName("jdk.incubator.vector.DoubleVector");
                Class<?> species = Class.forName("jdk.incubator.vector.VectorSpecies");
                Object preferred = vector.getField("SPECIES_PREFERRED").get(null);
                expected = (int) species.getMethod("vectorBitSize").invoke(preferred) >= 256;
            }
            BufferedImage image = new BufferedImage(5, 1, BufferedImage.TYPE_INT_RGB);
            int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
            int[] source = {0, 0x641464, 0xfa64c8, 0x01ff03, 0x803f7f};
            System.arraycopy(source, 0, pixels, 0, source.length);
            double[][] matrix = {{2, 0, 0}, {0, 0.5, 0}, {0, 0, 1.5}};
            boolean active = ColorMatrixProcessor.processWithVectors(image, matrix, 1);
            if (active != expected) {
                throw new AssertionError("Expected vector activation " + expected + ", got " + active);
            }
            System.arraycopy(source, 0, pixels, 0, source.length);
            ColorMatrixProcessor.process(image, matrix, 1);
            if (!Arrays.equals(new int[]{0, 0xc80a96, 0xff32ff, 0x027f04, 0xff1fbe}, pixels)) {
                throw new AssertionError("Packaged matrix changed scalar pixel results: " + Arrays.toString(pixels));
            }
        }
    }
}
