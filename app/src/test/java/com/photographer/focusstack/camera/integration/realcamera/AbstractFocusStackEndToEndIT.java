package com.photographer.focusstack.camera.integration.realcamera;

import com.photographer.focusstack.analysis.service.ImageJStackMerger;
import com.photographer.focusstack.analysis.service.StackMerger;
import com.photographer.focusstack.common.camera.*;
import com.photographer.focusstack.cr3.Cr3Exception;
import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.decode.Cr3Decoder;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Abstract base class for end-to-end focus stacking integration tests.
 *
 * <p>This test performs a <strong>complete focus stacking workflow</strong>:
 * <ol>
 *   <li>Capture multiple CR3 RAW images at different focus positions</li>
 *   <li>Transfer the raw image data from the camera</li>
 *   <li>Decode CR3 files to full-resolution {@link BufferedImage}s</li>
 *   <li>Stack the images using Laplacian-based focus merging</li>
 *   <li>Save the result to disk as a TIFF file</li>
 * </ol>
 *
 * <p>Subclasses provide the {@link CameraBackend} implementation (USB/PTP, CCAPI, etc.).
 *
 * <p>Run with:
 * <pre>
 *   mvn verify -pl app -am -Djava.awt.headless=true \
 *       -DexcludedGroups= -Dgroups=real-camera
 * </pre>
 */
@RealCamera
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
abstract class AbstractFocusStackEndToEndIT {

    private static final Logger log = LoggerFactory.getLogger(AbstractFocusStackEndToEndIT.class);

    /** Number of frames to capture for the focus stack. */
    private static final int STACK_SIZE = 5;

    /** Focus step magnitude between frames. */
    private static final int FOCUS_STEP = 2;

    /** Output directory for stacked results. */
    private static final Path OUTPUT_DIR = Path.of("target/focus-stack-results");

    private final StackMerger stackMerger = new ImageJStackMerger();

    /** Subclasses return the connected backend (USB/PTP, CCAPI, …). */
    protected abstract CameraBackend backend();

    /** Subclasses return a short identifier for the transport (e.g. "usb", "ccapi"). */
    protected abstract String transportName();

    @BeforeAll
    static void createOutputDir() throws IOException {
        Files.createDirectories(OUTPUT_DIR);
    }

    @BeforeEach
    void logTest(TestInfo info) {
        log.info("▶ Running: {}", info.getDisplayName());
    }

    /**
     * End-to-end focus stacking test: capture → transfer → decode → stack → save.
     */
    @Test
    @Order(1)
    void endToEndFocusStack() throws Exception {
        CameraBackend b = backend();
        assumeTrue(b.supports(Operation.CAPTURE), "Camera must support CAPTURE");
        assumeTrue(b.supports(Operation.FOCUS_CONTROL), "Camera must support FOCUS_CONTROL");

        log.info("═══════════════════════════════════════════════════════════════");
        log.info("  END-TO-END FOCUS STACK TEST via {}", transportName().toUpperCase());
        log.info("  Stack size: {} frames, focus step: {}", STACK_SIZE, FOCUS_STEP);
        log.info("═══════════════════════════════════════════════════════════════");

        // Ensure live view is stopped (may block capture on some cameras)
        b.stopLiveView();
        Thread.sleep(500);

        // ── Step 1: Capture CR3 images at different focus positions ─────────
        log.info("\n▸ STEP 1: Capturing {} CR3 images...", STACK_SIZE);
        List<CaptureResult> captures = new ArrayList<>();
        List<byte[]> rawCr3Data = new ArrayList<>();

        Instant captureStart = Instant.now();

        // Move focus to starting position
        b.setFocus(new FocusCommand(FocusCommand.Direction.NEAR, FOCUS_STEP * STACK_SIZE / 2));
        Thread.sleep(300);

        for (int i = 0; i < STACK_SIZE; i++) {
            log.info("  Capturing frame {}/{}...", i + 1, STACK_SIZE);
            Instant frameStart = Instant.now();

            CaptureResult result = b.capture();
            assertNotNull(result, "CaptureResult must not be null for frame " + (i + 1));
            assertNotNull(result.imageId(), "imageId must be set for frame " + (i + 1));

            captures.add(result);

            // Check if image data was transferred inline (USB/PTP does this)
            if (result.imageData() != null && result.imageData().length > 0) {
                rawCr3Data.add(result.imageData());
                log.info("    Frame {}: {} bytes transferred inline, took {} ms",
                        i + 1, result.imageData().length,
                        Duration.between(frameStart, Instant.now()).toMillis());
            } else {
                log.info("    Frame {}: imageId={}, file={} (requires separate download)",
                        i + 1, result.imageId(), result.fileName());
            }

            // Move focus for next frame (except after last)
            if (i < STACK_SIZE - 1) {
                b.setFocus(new FocusCommand(FocusCommand.Direction.FAR, FOCUS_STEP));
                Thread.sleep(200);
            }
        }

        Duration captureDuration = Duration.between(captureStart, Instant.now());
        log.info("  Capture phase complete: {} frames in {} ms", captures.size(), captureDuration.toMillis());

        // Verify we got image data
        assumeTrue(!rawCr3Data.isEmpty(),
                "No inline image data received — backend may require separate download (not implemented in this test)");
        assertEquals(STACK_SIZE, rawCr3Data.size(), "Expected " + STACK_SIZE + " CR3 files");

        // ── Step 2: Decode CR3 files to BufferedImages ──────────────────────
        log.info("\n▸ STEP 2: Decoding {} CR3 files to full-resolution images...", rawCr3Data.size());
        List<BufferedImage> decodedImages = new ArrayList<>();

        Instant decodeStart = Instant.now();
        Cr3DecodeOptions options = Cr3DecodeOptions.defaults()
                .outputMode(Cr3DecodeOptions.OutputMode.RAW_PROCESSED);

        for (int i = 0; i < rawCr3Data.size(); i++) {
            byte[] cr3Bytes = rawCr3Data.get(i);
            log.info("  Decoding frame {}/{} ({} bytes)...", i + 1, rawCr3Data.size(), cr3Bytes.length);

            Instant frameDecodeStart = Instant.now();
            BufferedImage img = decodeCr3(cr3Bytes, options);
            Duration frameDecodeDuration = Duration.between(frameDecodeStart, Instant.now());

            assertNotNull(img, "Decoded image must not be null for frame " + (i + 1));
            assertTrue(img.getWidth() > 0 && img.getHeight() > 0, "Decoded image must have positive dimensions");

            decodedImages.add(img);
            log.info("    Frame {}: {}×{} decoded in {} ms",
                    i + 1, img.getWidth(), img.getHeight(), frameDecodeDuration.toMillis());
        }

        Duration decodeDuration = Duration.between(decodeStart, Instant.now());
        log.info("  Decode phase complete: {} images in {} ms", decodedImages.size(), decodeDuration.toMillis());

        // ── Step 3: Stack images using Laplacian focus merge ────────────────
        log.info("\n▸ STEP 3: Stacking {} images using {} algorithm...",
                decodedImages.size(), stackMerger.name());

        Instant stackStart = Instant.now();
        BufferedImage stackedResult = stackMerger.mergeImages(decodedImages);
        Duration stackDuration = Duration.between(stackStart, Instant.now());

        assertNotNull(stackedResult, "Stacked result must not be null");
        assertEquals(decodedImages.getFirst().getWidth(), stackedResult.getWidth(), "Width must match input");
        assertEquals(decodedImages.getFirst().getHeight(), stackedResult.getHeight(), "Height must match input");

        log.info("  Stack phase complete: {}×{} in {} ms",
                stackedResult.getWidth(), stackedResult.getHeight(), stackDuration.toMillis());

        // ── Step 4: Save result to disk ─────────────────────────────────────
        log.info("\n▸ STEP 4: Saving stacked result...");
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String outputFileName = String.format("focus_stack_%s_%s_%dframes.png",
                transportName(), timestamp, STACK_SIZE);
        Path outputPath = OUTPUT_DIR.resolve(outputFileName);

        ImageIO.write(stackedResult, "PNG", outputPath.toFile());
        assertTrue(Files.exists(outputPath), "Output file must exist");
        long outputSize = Files.size(outputPath);

        log.info("  Result saved: {}", outputPath.toAbsolutePath());
        log.info("  Output size: {} bytes ({} MB)", outputSize, outputSize / (1024 * 1024));

        // ── Summary ─────────────────────────────────────────────────────────
        Duration totalDuration = Duration.between(captureStart, Instant.now());
        log.info("\n═══════════════════════════════════════════════════════════════");
        log.info("  FOCUS STACK COMPLETE");
        log.info("  Frames:     {}", STACK_SIZE);
        log.info("  Resolution: {}×{}", stackedResult.getWidth(), stackedResult.getHeight());
        log.info("  Capture:    {} ms", captureDuration.toMillis());
        log.info("  Decode:     {} ms", decodeDuration.toMillis());
        log.info("  Stack:      {} ms", stackDuration.toMillis());
        log.info("  Total:      {} ms", totalDuration.toMillis());
        log.info("  Output:     {}", outputPath.toAbsolutePath());
        log.info("═══════════════════════════════════════════════════════════════");

        // Return focus to approximate starting position
        b.setFocus(new FocusCommand(FocusCommand.Direction.NEAR, FOCUS_STEP * STACK_SIZE / 2));
    }

    /**
     * Decodes a CR3 byte array into a BufferedImage using full RAW processing.
     */
    private BufferedImage decodeCr3(byte[] cr3Bytes, Cr3DecodeOptions options)
            throws IOException, Cr3Exception {
        Cr3Decoder decoder = new Cr3Decoder();
        try (MemoryCacheImageInputStream input =
                     new MemoryCacheImageInputStream(new ByteArrayInputStream(cr3Bytes))) {
            decoder.setInput(input);
            return decoder.decode(options);
        }
    }
}
