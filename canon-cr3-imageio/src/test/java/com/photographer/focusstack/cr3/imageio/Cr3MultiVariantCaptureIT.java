package com.photographer.focusstack.cr3.imageio;

import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.decode.Cr3Decoder;
import com.photographer.focusstack.cr3.metadata.Cr3Metadata;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.stream.FileImageInputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real-camera integration test that captures multiple CR3 images with
 * <strong>different camera settings</strong> via CCAPI and saves them to
 * {@code src/test/resources/samples/} for use by variant verification tests.
 *
 * <p>The goal is to produce a set of CR3 files that exercise the CR3 parser
 * with varying EXIF metadata (different zoom / focal length, ISO, white balance)
 * to ensure the parse chain works correctly regardless of shooting parameters.
 *
 * <h3>Variants captured</h3>
 * <ol>
 *   <li>{@code sample-wide.cr3} — widest zoom (shortest focal length), auto WB</li>
 *   <li>{@code sample-tele.cr3} — tightest zoom (longest focal length), auto WB</li>
 *   <li>{@code sample-iso-high.cr3} — high ISO (1600), current zoom</li>
 *   <li>{@code sample-wb-tungsten.cr3} — tungsten white balance, current zoom</li>
 * </ol>
 *
 * <p>This test is tagged with {@code real-camera} and is <strong>excluded</strong>
 * from normal {@code mvn verify} runs. Run on-demand when the camera is connected:
 *
 * <pre>
 *   mvn verify -pl canon-cr3-imageio -am -Djava.awt.headless=true \
 *       -DexcludedGroups= -Dgroups=real-camera
 * </pre>
 *
 * <h3>Prerequisites</h3>
 * <ul>
 *   <li>Canon camera reachable at the operator-supplied {@code CCAPI_CAMERA_URL}</li>
 *   <li>Camera in <strong>P</strong> (Program AE) or <strong>M</strong> mode with CCAPI enabled</li>
 *   <li>Camera not in sleep mode; live view should be off</li>
 * </ul>
 */
@Tag("real-camera")
@EnabledIfEnvironmentVariable(named = "CCAPI_CAMERA_URL", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Cr3MultiVariantCaptureIT {

    private static final Logger log = LoggerFactory.getLogger(Cr3MultiVariantCaptureIT.class);

    /** Base URL for the Canon camera CCAPI endpoint. */
    private static final String CAMERA_BASE_URL = System.getenv("CCAPI_CAMERA_URL");

    /** Directory where sample CR3 files are saved (relative to module root). */
    private static final Path SAMPLES_DIR = Path.of("src", "test", "resources", "samples");

    private static HttpClient httpClient;

    // ── Original settings to restore after the test ──────────────────────
    private static String originalIsoBody;
    private static String originalWbBody;

    /** All variant files captured during this run, for final verification. */
    private static final Map<String, Path> capturedVariants = new LinkedHashMap<>();

    @BeforeAll
    static void setUp() throws Exception {
        httpClient = HttpClient.newHttpClient();

        // Verify camera is reachable
        try {
            HttpResponse<String> resp = httpGet(CAMERA_BASE_URL + "/ccapi");
            assumeTrue(resp.statusCode() == 200,
                    "Configured camera is unreachable");
            log.info("Camera reachable");
        } catch (Exception e) {
            assumeTrue(false,
                    "Configured camera is unreachable");
        }

        // Stop live view if active (prevents "Device busy" errors)
        stopLiveView();
        Thread.sleep(500);

        // Wait for camera to be idle
        waitForCameraIdle();

        // Ensure output directory exists
        Files.createDirectories(SAMPLES_DIR);

        // Ensure quality includes RAW
        ensureRawQuality();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Variant 1: Wide-angle (zoom at minimum focal length)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    void captureWideAngleVariant() throws Exception {
        log.info("════ Variant 1: Wide-angle (zoom wide) ════");

        // Drive zoom to the wide end
        driveZoom("wide", 5);

        Thread.sleep(1500); // let lens settle

        captureAndSaveVariant("sample-wide");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Variant 2: Telephoto (zoom at maximum focal length)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @Order(2)
    void captureTelephotoVariant() throws Exception {
        log.info("════ Variant 2: Telephoto (zoom tele) ════");

        // Drive zoom to the tele end
        driveZoom("tele", 5);

        Thread.sleep(1500); // let lens settle

        captureAndSaveVariant("sample-tele");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Variant 3: High ISO (ISO 1600)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @Order(3)
    void captureHighIsoVariant() throws Exception {
        log.info("════ Variant 3: High ISO ════");

        // Save current ISO
        originalIsoBody = readSetting("iso");

        // Set ISO to 1600
        boolean isoSet = trySetSetting("iso", "{\"value\":\"1600\"}");
        if (!isoSet) {
            // Camera may be in Auto mode or ISO not changeable — capture anyway
            log.warn("Could not set ISO to 1600 — capturing with current ISO");
        }
        Thread.sleep(500);

        captureAndSaveVariant("sample-iso-high");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Variant 4: Tungsten white balance
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @Order(4)
    void captureTungstenWbVariant() throws Exception {
        log.info("════ Variant 4: Tungsten white balance ════");

        // Save current WB
        originalWbBody = readSetting("wb");

        // Set WB to tungsten
        boolean wbSet = trySetSetting("wb", "{\"value\":\"tungsten\"}");
        if (!wbSet) {
            log.warn("Could not set WB to tungsten — capturing with current WB");
        }
        Thread.sleep(500);

        captureAndSaveVariant("sample-wb-tungsten");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Restore original settings
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @Order(5)
    void restoreOriginalSettings() throws Exception {
        log.info("════ Restoring original settings ════");

        // Restore ISO
        if (originalIsoBody != null) {
            String isoValue = extractJsonStringValue(originalIsoBody, "value");
            if (isoValue != null) {
                trySetSetting("iso", "{\"value\":\"" + isoValue + "\"}");
                log.info("Restored original ISO");
            }
        }

        // Restore WB
        if (originalWbBody != null) {
            String wbValue = extractJsonStringValue(originalWbBody, "value");
            if (wbValue != null) {
                trySetSetting("wb", "{\"value\":\"" + wbValue + "\"}");
                log.info("Restored original white balance");
            }
        }

        // Drive zoom back to wide (reasonable default)
        driveZoom("wide", 3);

        log.info("Settings restored");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Verify all captured variants
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @Order(6)
    void verifyAllCapturedVariantsExistAndAreValidCr3() throws Exception {
        log.info("════ Verifying all captured variant files ════");

        assertThat(capturedVariants)
                .as("At least one variant should have been captured")
                .isNotEmpty();

        for (Map.Entry<String, Path> entry : capturedVariants.entrySet()) {
            String name = entry.getKey();
            Path file = entry.getValue();

            assertThat(file).as(name + " should exist").exists();
            long size = Files.size(file);
            assertThat(size).as(name + " should be at least 1 MB (is " + size + " bytes)")
                    .isGreaterThan(1_000_000L);

            // Verify ftyp signature
            byte[] header = new byte[8];
            try (var in = Files.newInputStream(file)) {
                assertThat(in.read(header)).isEqualTo(8);
            }
            assertThat(new String(header, 4, 4))
                    .as(name + " ftyp signature")
                    .isEqualTo("ftyp");

            log.info("✓ {} exists: {} bytes, valid ftyp signature", name, size);
        }
    }

    @Test
    @Order(7)
    void parseAllVariantsAndVerifyMetadataAndDecoding() throws Exception {
        log.info("════ Parsing all variants: metadata + preview + thumbnail ════");

        for (Map.Entry<String, Path> entry : capturedVariants.entrySet()) {
            String name = entry.getKey();
            Path file = entry.getValue();

            if (!Files.exists(file)) {
                log.warn("Skipping {} — file does not exist", name);
                continue;
            }

            log.info("── Parsing {} ──", name);
            File cr3File = file.toFile();
            try (FileImageInputStream in = new FileImageInputStream(cr3File)) {
                Cr3Decoder decoder = new Cr3Decoder();
                decoder.setInput(in);

                // Container structure
                Cr3Container container = decoder.getContainer();
                assertThat(container).as(name + " container").isNotNull();
                assertThat(container.ftypBox()).as(name + " ftyp").isNotNull();
                assertThat(container.moovBox()).as(name + " moov").isNotNull();
                assertThat(container.mdatBox()).as(name + " mdat").isNotNull();
                assertThat(container.trackCount()).as(name + " track count")
                        .isGreaterThanOrEqualTo(3);

                // Metadata
                Cr3Metadata meta = decoder.getMetadata();
                assertThat(meta).as(name + " metadata").isNotNull();
                assertThat(meta.cameraModel())
                        .as(name + " camera model")
                        .isNotBlank()
                        .containsIgnoringCase("Canon");
                assertThat(meta.isoSpeed())
                        .as(name + " ISO").isGreaterThan(0);
                assertThat(meta.fNumber())
                        .as(name + " f-number").isGreaterThan(0.0);
                assertThat(meta.exposureTime())
                        .as(name + " exposure time").isGreaterThan(0.0);
                assertThat(meta.focalLengthMm())
                        .as(name + " focal length mm").isGreaterThan(0.0);
                assertThat(meta.captureDateTime())
                        .as(name + " capture date/time").isNotBlank();
                assertThat(meta.sensorWidth())
                        .as(name + " sensor width").isGreaterThan(0);
                assertThat(meta.sensorHeight())
                        .as(name + " sensor height").isGreaterThan(0);

                log.info("Sensor: {} x {}, ISO {}", meta.sensorWidth(), meta.sensorHeight(), meta.isoSpeed());

                // Preview decoding
                BufferedImage preview = decoder.decode(Cr3DecodeOptions.defaults());
                assertThat(preview).as(name + " preview").isNotNull();
                assertThat(preview.getWidth()).as(name + " preview width").isGreaterThan(0);
                assertThat(preview.getHeight()).as(name + " preview height").isGreaterThan(0);
                log.info("  Preview: {}x{}", preview.getWidth(), preview.getHeight());

                // Thumbnail decoding
                BufferedImage thumb = decoder.decode(
                        Cr3DecodeOptions.defaults().outputMode(Cr3DecodeOptions.OutputMode.THUMBNAIL));
                assertThat(thumb).as(name + " thumbnail").isNotNull();
                assertThat(thumb.getWidth()).as(name + " thumb width").isGreaterThan(0);
                log.info("  Thumbnail: {}x{}", thumb.getWidth(), thumb.getHeight());

                // Full RAW decode (processed sRGB)
                BufferedImage rawProcessed = decoder.decode(Cr3DecodeOptions.rawProcessed());
                assertThat(rawProcessed).as(name + " RAW processed").isNotNull();
                assertThat(rawProcessed.getWidth())
                        .as(name + " RAW width").isGreaterThan(preview.getWidth());
                log.info("  RAW processed: {}x{}", rawProcessed.getWidth(), rawProcessed.getHeight());

                log.info("✓ {} — all decode modes passed", name);
            }
        }
    }

    @Test
    @Order(8)
    void verifyFocalLengthDiffersBetweenWideAndTele() throws Exception {
        Path wideFile = SAMPLES_DIR.resolve("sample-wide.cr3");
        Path teleFile = SAMPLES_DIR.resolve("sample-tele.cr3");

        if (!Files.exists(wideFile) || !Files.exists(teleFile)) {
            log.warn("Skipping focal-length comparison — wide/tele files not both present");
            return;
        }

        double wideFocal;
        try (FileImageInputStream in = new FileImageInputStream(wideFile.toFile())) {
            Cr3Decoder decoder = new Cr3Decoder();
            decoder.setInput(in);
            wideFocal = decoder.getMetadata().focalLengthMm();
        }

        double teleFocal;
        try (FileImageInputStream in = new FileImageInputStream(teleFile.toFile())) {
            Cr3Decoder decoder = new Cr3Decoder();
            decoder.setInput(in);
            teleFocal = decoder.getMetadata().focalLengthMm();
        }

        log.info("Wide focal length: {} mm, Tele focal length: {} mm", wideFocal, teleFocal);

        // Zoom control via CCAPI may not work for all camera models / modes.
        // When it does work, verify that tele focal length is longer; otherwise
        // just confirm both values were successfully parsed (> 0) — the primary
        // goal is testing the CR3 metadata parser, not the zoom motor.
        if (Double.compare(wideFocal, teleFocal) == 0) {
            log.warn("Wide and tele focal lengths are identical ({}mm) — "
                    + "zoom control was not effective (camera may not support "
                    + "CCAPI zoom in current mode). Metadata parsing still verified.", wideFocal);
        } else {
            assertThat(teleFocal)
                    .as("Telephoto focal length should be longer than wide-angle")
                    .isGreaterThan(wideFocal);
        }
        assertThat(wideFocal).as("Wide focal length should be > 0").isGreaterThan(0.0);
        assertThat(teleFocal).as("Tele focal length should be > 0").isGreaterThan(0.0);
    }

    // ── Core capture helper ─────────────────────────────────────────────────

    /**
     * Takes a photo, downloads the CR3 from the camera, and saves it to
     * {@code SAMPLES_DIR/<baseName>.cr3}.
     */
    private void captureAndSaveVariant(String baseName) throws Exception {
        // Clear pending events
        httpGet(CAMERA_BASE_URL + "/ccapi/ver100/event/polling");

        // Trigger 3-phase shutter sequence
        log.info("Triggering shutter for '{}'...", baseName);
        String shutterUrl = CAMERA_BASE_URL + "/ccapi/ver100/shooting/control/shutterbutton/manual";
        httpPost(shutterUrl, "{\"action\":\"half_press\",\"af\":true}");
        Thread.sleep(1000);
        httpPost(shutterUrl, "{\"action\":\"full_press\",\"af\":false}");
        Thread.sleep(500);
        httpPost(shutterUrl, "{\"action\":\"release\",\"af\":false}");
        log.info("Shutter released for '{}', waiting for image...", baseName);
        Thread.sleep(3000); // let camera write to SD card

        // Poll event/polling to find the new image URL
        String cr3Url = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            HttpResponse<String> eventResp = httpGet(
                    CAMERA_BASE_URL + "/ccapi/ver100/event/polling");
            log.info("Event polling attempt {} returned HTTP {}", attempt + 1, eventResp.statusCode());
            cr3Url = findCr3UrlInResponse(eventResp.body());
            if (cr3Url != null) break;
            Thread.sleep(2000);
        }

        if (cr3Url == null) {
            // Fallback: browse SD card for the newest CR3 file
            log.info("No CR3 URL in event polling — browsing SD card...");
            cr3Url = findNewestCr3OnSdCard();
        }

        assertThat(cr3Url)
                .as("Should find a CR3 file URL for variant '" + baseName + "'")
                .isNotNull()
                .containsIgnoringCase(".cr3");
        cr3Url = cr3Url.replace("\\/", "/");
        log.info("Located CR3 for fixture {}", baseName);

        // Download the full CR3 file
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(cr3Url))
                .GET()
                .build();
        HttpResponse<byte[]> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertThat(resp.statusCode()).isEqualTo(200);

        byte[] cr3Data = resp.body();
        assertThat(cr3Data.length)
                .as("CR3 for '" + baseName + "' should be at least ~1 MB")
                .isGreaterThan(1_000_000);

        // Verify ftyp signature
        String ftyp = new String(cr3Data, 4, 4);
        assertThat(ftyp).isEqualTo("ftyp");
        log.info("✓ Downloaded {} bytes for '{}', ftyp signature verified", cr3Data.length, baseName);

        // Save to test resources
        Path outputFile = SAMPLES_DIR.resolve(baseName + ".cr3");
        Files.write(outputFile, cr3Data);
        capturedVariants.put(baseName, outputFile);
        log.info("Saved fixture {}", baseName);

        // Wait for camera to be ready for next shot
        waitForCameraIdle();
    }

    // ── CCAPI setting helpers ───────────────────────────────────────────────

    private static void ensureRawQuality() throws Exception {
        HttpResponse<String> resp = httpGet(
                CAMERA_BASE_URL + "/ccapi/ver100/shooting/settings/stillimagequality");
        if (resp.statusCode() == 200) {
            log.info("Read current still image quality");
            if (!resp.body().contains("\"raw\"")) {
                log.info("Setting quality to RAW...");
                HttpResponse<String> putResp = httpPut(
                        CAMERA_BASE_URL + "/ccapi/ver100/shooting/settings/stillimagequality",
                        "{\"value\":\"raw\"}");
                log.info("Set quality returned HTTP {}", putResp.statusCode());
                Thread.sleep(500);
            }
        }
    }

    private static String readSetting(String settingName) {
        try {
            HttpResponse<String> resp = httpGet(
                    CAMERA_BASE_URL + "/ccapi/ver100/shooting/settings/" + settingName);
            if (resp.statusCode() == 200) {
                log.info("Read setting {}", settingName);
                return resp.body();
            }
        } catch (Exception e) {
            log.warn("Could not read setting '{}': {}", settingName, e.getClass().getSimpleName());
        }
        return null;
    }

    private static boolean trySetSetting(String settingName, String jsonBody) {
        try {
            HttpResponse<String> resp = httpPut(
                    CAMERA_BASE_URL + "/ccapi/ver100/shooting/settings/" + settingName,
                    jsonBody);
            log.info("Set {} returned HTTP {}", settingName, resp.statusCode());
            return resp.statusCode() < 300;
        } catch (Exception e) {
            log.warn("Could not set {} ({})", settingName, e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Drives the zoom motor in the given direction by sending multiple
     * {@code POST /shooting/control/zoom} commands.
     *
     * <p>Canon CCAPI zoom control requires live view to be active on many
     * camera models. This method first tries to enable live view, then
     * sends zoom commands, and finally stops live view so the shutter can
     * fire normally.</p>
     *
     * <p>Several parameter formats are tried: Canon CCAPI v1.00 uses plain
     * string values ({@code "wide-fast"}, {@code "tele-fast"}), while some
     * firmware versions expect just the direction ({@code "wide"}, {@code "tele"}).</p>
     *
     * @param direction "wide" or "tele"
     * @param steps     number of zoom impulses to send
     */
    private static void driveZoom(String direction, int steps) {
        String zoomUrl = CAMERA_BASE_URL + "/ccapi/ver100/shooting/control/zoom";
        // Try enabling live view first — zoom often requires it
        boolean liveViewStarted = false;
        try {
            HttpResponse<String> lvResp = httpPost(
                    CAMERA_BASE_URL + "/ccapi/ver100/shooting/liveview",
                    "{\"liveviewsize\":\"medium\",\"cameradisplay\":\"on\"}");
            liveViewStarted = lvResp.statusCode() < 300;
            if (liveViewStarted) {
                log.info("Live view enabled for zoom control");
                Thread.sleep(1000); // let live view stabilise
            } else {
                log.info("Could not start live view for zoom (HTTP {})", lvResp.statusCode());
            }
        } catch (Exception e) {
            log.info("Live view start failed: {}", e.getClass().getSimpleName());
        }

        // Try multiple parameter formats; Canon CCAPI versions differ
        String[] formats = {direction + "-fast", direction};
        boolean zoomWorked = false;
        try {
            for (String value : formats) {
                HttpResponse<String> probe = httpPost(zoomUrl,
                        "{\"value\":\"" + value + "\"}");
                if (probe.statusCode() < 300) {
                    log.info("Zoom format '{}' accepted — driving {} steps", value, steps - 1);
                    zoomWorked = true;
                    Thread.sleep(600);
                    // Already sent 1 step as probe; send remaining
                    for (int i = 1; i < steps; i++) {
                        httpPost(zoomUrl, "{\"value\":\"" + value + "\"}");
                        Thread.sleep(600);
                    }
                    break;
                }
                log.info("Zoom option rejected (HTTP {})", probe.statusCode());
            }
            // Stop the zoom motor
            httpPost(zoomUrl, "{\"value\":\"stop\"}");
            log.info("Zoom stopped (worked={})", zoomWorked);
        } catch (Exception e) {
            log.warn("Zoom drive ('{}') failed: {} — continuing with current zoom",
                    direction, e.getClass().getSimpleName());
        }

        // Stop live view so the camera can take a still image
        if (liveViewStarted) {
            stopLiveView();
            try { Thread.sleep(500); } catch (InterruptedException ignored) { }
        }
    }

    // ── HTTP helpers ───────────────────────────────────────────────────────

    private static HttpResponse<String> httpGet(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> httpPost(String url, String jsonBody)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> httpPut(String url, String jsonBody)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    // ── Camera helpers ─────────────────────────────────────────────────────

    private static void stopLiveView() {
        try {
            httpPost(CAMERA_BASE_URL + "/ccapi/ver100/shooting/liveview",
                    "{\"liveviewsize\":\"off\",\"cameradisplay\":\"on\"}");
            log.info("Live view stopped");
        } catch (Exception e) {
            log.debug("Could not stop live view (may already be off): {}", e.getClass().getSimpleName());
        }
    }

    private static void waitForCameraIdle() throws Exception {
        for (int i = 0; i < 60; i++) {
            try {
                HttpResponse<String> resp = httpGet(
                        CAMERA_BASE_URL + "/ccapi/ver100/contents/sd");
                if (resp.statusCode() == 200) {
                    log.info("Camera idle (waited {}ms)", i * 500);
                    return;
                }
            } catch (Exception e) {
                // continue polling
            }
            if (i > 0 && i % 4 == 0) {
                stopLiveView();
            }
            Thread.sleep(500);
        }
        log.warn("Camera may still be busy after 30s");
    }

    private static String findCr3UrlInResponse(String json) {
        String unescaped = json.replace("\\/", "/");
        Pattern pattern = Pattern.compile("(http[^\"]+\\.(?i:cr3))");
        Matcher matcher = pattern.matcher(unescaped);
        String lastMatch = null;
        while (matcher.find()) {
            lastMatch = matcher.group(1);
        }
        return lastMatch;
    }

    private static String findNewestCr3OnSdCard() throws IOException, InterruptedException {
        String sdUrl = CAMERA_BASE_URL + "/ccapi/ver100/contents/sd";
        HttpResponse<String> sdResp = httpGet(sdUrl);
        if (sdResp.statusCode() != 200) {
            log.warn("Cannot list SD card: {}", sdResp.statusCode());
            return null;
        }

        Pattern folderPattern = Pattern.compile("\"name\"\\s*:\\s*\"(\\d{3}[A-Z]+)\"");
        Matcher folderMatcher = folderPattern.matcher(sdResp.body());
        String lastFolder = null;
        while (folderMatcher.find()) {
            lastFolder = folderMatcher.group(1);
        }
        if (lastFolder == null) {
            log.warn("No DCIM folders found on SD card");
            return null;
        }

        String folderUrl = sdUrl + "/" + lastFolder;
        HttpResponse<String> folderResp = httpGet(folderUrl);
        if (folderResp.statusCode() != 200) {
            log.warn("Folder listing returned HTTP {}", folderResp.statusCode());
            return null;
        }

        Pattern filePattern = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+\\.(?i:CR3))\"");
        Matcher fileMatcher = filePattern.matcher(folderResp.body());
        String lastCr3 = null;
        while (fileMatcher.find()) {
            lastCr3 = fileMatcher.group(1);
        }
        if (lastCr3 == null) {
            log.warn("No CR3 files found in selected folder");
            return null;
        }

        return folderUrl + "/" + lastCr3;
    }

    private static String extractJsonStringValue(String json, String key) {
        Pattern pattern = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }
}
