package com.photographer.focusstack.camera.integration.realcamera;

import com.photographer.focusstack.common.camera.CaptureResult;
import com.photographer.ptp.backend.PtpCameraBackend;
import com.photographer.ptp.backend.PtpCameraBackendFactory;
import com.photographer.ptp.core.PtpException;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real-camera integration test that captures CR3 sample images from a Canon
 * EOS R connected via USB/PTP and saves them to
 * {@code canon-cr3-imageio/src/test/resources/samples/} for use by
 * {@code Cr3VariantVerificationTest} and other CR3 decoder tests.
 *
 * <p>Different EOS R settings produce structurally different CR3 files.
 * To exercise the CR3 parser/decoder thoroughly we vary the settings that
 * have the largest impact on the file format and embedded metadata:
 *
 * <table>
 *   <caption>Variants captured</caption>
 *   <tr><th>File</th><th>Setting varied</th><th>What changes in the CR3</th></tr>
 *   <tr><td>{@code eosr-iso-low.cr3}</td>   <td>ISO 100</td>   <td>CMT2 ExifIFD ISO tag, noise profile, gain pre-mul</td></tr>
 *   <tr><td>{@code eosr-iso-mid.cr3}</td>   <td>ISO 800</td>   <td>CMT2 ExifIFD ISO tag, different gain values</td></tr>
 *   <tr><td>{@code eosr-iso-high.cr3}</td>  <td>ISO 6400</td>  <td>CMT2 ExifIFD ISO tag, high-ISO noise reduction may add CTMD entries</td></tr>
 * </table>
 *
 * <p>Image quality (CR3 vs C-RAW) and image size (RAW / M-RAW / S-RAW) have
 * even larger impact on the format (different CRX codec versions, different
 * pixel dimensions) but require setting {@code DPC_EOS_IMAGE_QUALITY} with
 * a model-specific value.  Capture the equivalent C-RAW / M-RAW samples by
 * dialling those settings on the camera body and re-running this IT once
 * per setting variation.</p>
 *
 * <h3>Run with</h3>
 * <pre>
 *   mvn verify -pl app -am -Djava.awt.headless=true \
 *       -DexcludedGroups= -Dgroups=real-camera \
 *       -Dit.test=EosRSampleCaptureIT
 * </pre>
 */
@RealCamera
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EosRSampleCaptureIT {

    private static final Logger log = LoggerFactory.getLogger(EosRSampleCaptureIT.class);

    /**
     * Directory where sample CR3 files are saved — points at the
     * canon-cr3-imageio module's test resources so the existing
     * Cr3VariantVerificationTest picks them up automatically.
     */
    private static final Path SAMPLES_DIR =
            Path.of("../canon-cr3-imageio/src/test/resources/samples");

    // Canon EOS raw ISO property values (DPC_EOS_ISO = 0xD103).
    // These follow Canon's standard EOS encoding shared by R-series bodies.
    private static final int ISO_100  = 0x48;
    private static final int ISO_800  = 0x60;
    private static final int ISO_6400 = 0x78;

    private PtpCameraBackend backend;
    private int originalIsoRaw = -1;

    @BeforeAll
    void openCamera() throws Exception {
        try {
            backend = PtpCameraBackendFactory.createUsbBackend();
            log.info("USB camera: {}", backend.getInfo().model());
        } catch (PtpException e) {
            assumeTrue(false, "No USB Canon camera found: " + e.getMessage());
        }
        assumeTrue(backend != null, "No USB Canon camera found");
        Files.createDirectories(SAMPLES_DIR);

        // Remember original ISO so we can restore it
        try {
            originalIsoRaw = backend.getCamera().getIsoRaw();
            log.info("Original ISO raw value: 0x{}", Integer.toHexString(originalIsoRaw));
        } catch (Exception e) {
            log.warn("Could not read original ISO: {}", e.getMessage());
        }
    }

    @AfterAll
    void restoreSettings() {
        if (backend != null && originalIsoRaw > 0) {
            try {
                backend.getCamera().setIso(originalIsoRaw);
                log.info("Restored original ISO 0x{}", Integer.toHexString(originalIsoRaw));
            } catch (Exception e) {
                log.warn("Could not restore ISO: {}", e.getMessage());
            }
        }
    }

    @Test
    @Order(1)
    @DisplayName("Capture eosr-iso-low.cr3 — ISO 100")
    void captureLowIso() throws Exception {
        setIsoSafely(ISO_100, "ISO 100");
        captureAndSave("eosr-iso-low.cr3");
    }

    @Test
    @Order(2)
    @DisplayName("Capture eosr-iso-mid.cr3 — ISO 800")
    void captureMidIso() throws Exception {
        setIsoSafely(ISO_800, "ISO 800");
        captureAndSave("eosr-iso-mid.cr3");
    }

    @Test
    @Order(3)
    @DisplayName("Capture eosr-iso-high.cr3 — ISO 6400")
    void captureHighIso() throws Exception {
        setIsoSafely(ISO_6400, "ISO 6400");
        captureAndSave("eosr-iso-high.cr3");
    }

    @Test
    @Order(4)
    @DisplayName("Verify all saved EOS R samples have valid CR3 ftyp signature")
    void verifyAllSamples() throws Exception {
        for (String name : new String[]{
                "eosr-iso-low.cr3", "eosr-iso-mid.cr3", "eosr-iso-high.cr3"}) {
            Path file = SAMPLES_DIR.resolve(name);
            assertThat(file).as(name + " should exist").exists();

            long size = Files.size(file);
            assertThat(size).as(name + " size").isGreaterThan(1_000_000L);

            byte[] header = new byte[12];
            try (var in = Files.newInputStream(file)) {
                assertThat(in.read(header)).isEqualTo(12);
            }
            assertThat(new String(header, 4, 4))
                    .as(name + " ftyp signature").isEqualTo("ftyp");

            log.info("✓ {} — {} bytes, valid ftyp", name, size);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private void setIsoSafely(int rawIso, String label) {
        try {
            backend.getCamera().setIso(rawIso);
            log.info("Set {} (raw=0x{})", label, Integer.toHexString(rawIso));
            Thread.sleep(500);
        } catch (Exception e) {
            log.warn("Could not set {} (continuing with current setting): {}",
                    label, e.getMessage());
        }
    }

    private void captureAndSave(String filename) throws Exception {
        log.info("Capturing {}...", filename);

        CaptureResult captureResult = backend.capture();

        assertThat(captureResult.imageData())
                .as(filename + " image data should not be null")
                .isNotNull();
        assertThat(captureResult.imageData().length)
                .as(filename + " image data should be > 1 MB (full CR3)")
                .isGreaterThan(1_000_000);

        byte[] data = captureResult.imageData();
        assertThat(new String(data, 4, 4))
                .as(filename + " ftyp signature in memory")
                .isEqualTo("ftyp");

        Path outputFile = SAMPLES_DIR.resolve(filename);
        Files.write(outputFile, data);
        log.info("✓ {} — {} bytes saved to {}",
                filename, data.length, outputFile.toAbsolutePath());

        Thread.sleep(1000);
    }
}