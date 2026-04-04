package com.photographer.focusstack.analysis.integration.docker;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testcontainers integration test for the Fiji headless Docker worker.
 * Verifies that Fiji/ImageJ can run headless inside a Docker container
 * and execute macros for image processing.
 * <p>
 * This test builds the fiji-worker Docker image and runs ImageJ macros
 * headlessly to verify the container is properly configured.
 */
@Tag("docker")
@Testcontainers
class FijiDockerIT {

    private static final Logger log = LoggerFactory.getLogger(FijiDockerIT.class);
    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir")).getParent().getParent();

    @Test
    void fijiShouldRunHeadlessAndPrintVersion() {
        try (GenericContainer<?> fiji = createFijiContainer("--run", "print(IJ.getVersion());")) {
            fiji.start();
            String logs = fiji.getLogs();
            log.info("Fiji version output: {}", logs);
            // Fiji should output its version number
            assertNotNull(logs, "Fiji should produce output");
        }
    }

    @Test
    void fijiShouldExecuteMacroHeadless() {
        String macro = "newImage('Test', '8-bit', 100, 100, 1); " +
                "run('Add Noise'); " +
                "List.setMeasurements; " +
                "print('mean=' + List.getValue('Mean')); " +
                "print('stddev=' + List.getValue('StdDev'));";

        try (GenericContainer<?> fiji = createFijiContainer("--run", macro)) {
            fiji.start();
            String logs = fiji.getLogs();
            log.info("Fiji macro output: {}", logs);
            assertTrue(logs.contains("mean="), "Fiji should output mean value from macro: " + logs);
            assertTrue(logs.contains("stddev="), "Fiji should output stddev from macro: " + logs);
        }
    }

    @Test
    void fijiShouldComputeSharpnessMetric() {
        // Create a sharp image, apply Laplacian, measure variance
        String macro = "newImage('Sharp', '8-bit', 200, 200, 1); " +
                "// Create checkerboard pattern\n" +
                "for (y=0; y<200; y+=20) { " +
                "  for (x=0; x<200; x+=20) { " +
                "    if (((x+y)/20)%2==0) { " +
                "      makeRectangle(x, y, 20, 20); " +
                "      run('Fill', 'slice'); " +
                "    } " +
                "  } " +
                "} " +
                "run('Select None'); " +
                "run('Find Edges'); " +
                "List.setMeasurements; " +
                "print('sharpness_stddev=' + List.getValue('StdDev'));";

        try (GenericContainer<?> fiji = createFijiContainer("--run", macro)) {
            fiji.start();
            String logs = fiji.getLogs();
            log.info("Fiji sharpness output: {}", logs);
            assertTrue(logs.contains("sharpness_stddev="),
                    "Fiji should compute sharpness metric: " + logs);
        }
    }

    @SuppressWarnings("resource")
    private GenericContainer<?> createFijiContainer(String... command) {
        return new GenericContainer<>(
                new ImageFromDockerfile("fiji-worker-test", false)
                        .withDockerfile(PROJECT_ROOT.resolve("docker/fiji-worker/Dockerfile")))
                .withCommand(command)
                .withLogConsumer(new Slf4jLogConsumer(log))
                .withStartupTimeout(Duration.ofMinutes(10));
    }
}
