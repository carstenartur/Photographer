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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real-camera integration test that captures CR3 sample images from a Canon
 * camera connected via CCAPI and saves them to {@code src/test/resources/samples/}
 * for use by other tests (e.g. {@link CanonCr3ImageReaderTest}).
 *
 * <p>This test is tagged with {@code real-camera} and is <strong>excluded</strong>
 * from normal {@code mvn verify} runs. Run it on-demand when the camera is
 * powered on and connected:
 *
 * <pre>
 *   mvn verify -pl canon-cr3-imageio -am -Djava.awt.headless=true \
 *       -DexcludedGroups= -Dgroups=real-camera
 * </pre>
 *
 * <h3>What this test does</h3>
 * <ol>
 *   <li>Connects to the Canon G5 X Mark II via CCAPI</li>
 *   <li>Reads the current still-image quality setting</li>
 *   <li>Temporarily sets quality to {@code raw} (CR3 only) if needed</li>
 *   <li>Triggers the shutter via {@code shutterbutton/manual}</li>
 *   <li>Polls {@code event/polling} to discover the newly created CR3 file</li>
 *   <li>Downloads the full CR3 file from the camera's SD card</li>
 *   <li>Saves it to {@code src/test/resources/samples/sample.cr3}</li>
 *   <li>Restores the original image quality setting</li>
 *   <li>Verifies saved files exist with reasonable sizes</li>
 *   <li>Parses the saved CR3, reads all shooting-settings metadata (model, ISO,
 *       f-number, exposure time, focal length, capture date/time) and decodes
 *       the embedded PRVW preview + THMB thumbnail — proving the complete
 *       read chain works end-to-end after real-camera capture</li>
 * </ol>
 *
 * <h3>Prerequisites</h3>
 * <ul>
 *   <li>Canon camera reachable at {@value #CAMERA_BASE_URL}</li>
 *   <li>Camera in WiFi mode with CCAPI enabled</li>
 *   <li>Camera not in sleep mode</li>
 * </ul>
 */
@Tag("real-camera")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Cr3SampleCaptureIT {

    private static final Logger log = LoggerFactory.getLogger(Cr3SampleCaptureIT.class);

    /** Base URL for the Canon camera CCAPI endpoint. */
    private static final String CAMERA_BASE_URL = "http://192.168.0.206:8080";

    /** Directory where sample CR3 files are saved (relative to module root). */
    private static final Path SAMPLES_DIR = Path.of("src", "test", "resources", "samples");

    private static HttpClient httpClient;

    /** The original still-image quality value, so we can restore it after the test. */
    private static String originalQuality;

    /** URL of the captured CR3 file on the camera's SD card. */
    private static String capturedCr3Url;

    @BeforeAll
    static void setUp() throws Exception {
        httpClient = HttpClient.newHttpClient();

        // Verify camera is reachable
        try {
            HttpResponse<String> resp = httpGet(CAMERA_BASE_URL + "/ccapi");
            assumeTrue(resp.statusCode() == 200,
                    "Camera not reachable at " + CAMERA_BASE_URL);
            log.info("Camera reachable at {}", CAMERA_BASE_URL);
        } catch (Exception e) {
            assumeTrue(false,
                    "Camera not reachable at " + CAMERA_BASE_URL + ": " + e.getMessage());
        }

        // Stop live view if active (prevents "Device busy" errors)
        stopLiveView();
        Thread.sleep(500);

        // Wait for camera to be idle
        waitForCameraIdle();

        // Ensure output directory exists
        Files.createDirectories(SAMPLES_DIR);
    }

    @Test
    @Order(1)
    void setImageQualityToRaw() throws Exception {
        // Read current quality setting
        HttpResponse<String> resp = httpGet(
                CAMERA_BASE_URL + "/ccapi/ver100/shooting/settings/stillimagequality");
        assertThat(resp.statusCode()).isEqualTo(200);
        log.info("Current still image quality: {}", resp.body());

        // The G5X Mark II returns a nested object for the combined RAW+JPEG setting:
        //   {"value":{"raw":"raw","jpeg":"large_fine"},"ability":{...}}
        // A plain RAW-only camera returns: {"value":"raw","ability":[...]}
        // We store the raw response body as "originalQuality" for restore later.
        originalQuality = resp.body();

        boolean alreadyIncludesRaw = resp.body().contains("\"raw\"");
        if (alreadyIncludesRaw) {
            log.info("Camera already includes RAW in quality setting — no change needed");
        } else {
            // Try setting to raw (plain string format, for non-combined cameras)
            log.info("Setting still image quality to 'raw'...");
            HttpResponse<String> putResp = httpPut(
                    CAMERA_BASE_URL + "/ccapi/ver100/shooting/settings/stillimagequality",
                    "{\"value\":\"raw\"}");
            log.info("Set quality response: {} {}", putResp.statusCode(), putResp.body());
            // Only assert success when we actually attempted the change
            assertThat(putResp.statusCode()).isLessThan(300);
            Thread.sleep(500); // let camera apply the setting
        }
    }

    @Test
    @Order(2)
    void captureAndLocateCr3() throws Exception {
        // Clear pending events
        httpGet(CAMERA_BASE_URL + "/ccapi/ver100/event/polling");

        // Trigger 3-phase shutter sequence
        log.info("Triggering shutter...");
        String shutterUrl = CAMERA_BASE_URL + "/ccapi/ver100/shooting/control/shutterbutton/manual";
        httpPost(shutterUrl, "{\"action\":\"half_press\",\"af\":false}");
        Thread.sleep(500);
        httpPost(shutterUrl, "{\"action\":\"full_press\",\"af\":false}");
        Thread.sleep(500);
        httpPost(shutterUrl, "{\"action\":\"release\",\"af\":false}");
        log.info("Shutter released, waiting for image to be written...");
        Thread.sleep(2000); // let camera write to SD card

        // Poll event/polling to find the new image URL
        HttpResponse<String> eventResp = httpGet(
                CAMERA_BASE_URL + "/ccapi/ver100/event/polling");
        log.info("Event polling response: {}", eventResp.body());

        // Search for CR3 URLs in the event response
        capturedCr3Url = findCr3UrlInResponse(eventResp.body());

        if (capturedCr3Url == null) {
            // Fallback: browse SD card for the newest CR3 file
            log.info("No CR3 URL in event polling — browsing SD card...");
            capturedCr3Url = findNewestCr3OnSdCard();
        }

        assertThat(capturedCr3Url)
                .as("Should find a CR3 file URL on the camera")
                .isNotNull()
                .containsIgnoringCase(".cr3");
        // Ensure no JSON-escaped slashes remain before it is stored
        capturedCr3Url = capturedCr3Url.replace("\\/", "/");
        log.info("Found CR3 file: {}", capturedCr3Url);
    }

    @Test
    @Order(3)
    void downloadAndSaveCr3() throws Exception {
        assertThat(capturedCr3Url).as("CR3 URL must have been found in previous test").isNotNull();

        // Download the full CR3 file
        log.info("Downloading CR3 from {}...", capturedCr3Url);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(capturedCr3Url))
                .GET()
                .build();
        HttpResponse<byte[]> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertThat(resp.statusCode()).isEqualTo(200);

        byte[] cr3Data = resp.body();
        assertThat(cr3Data.length).isGreaterThan(1_000_000);  // CR3 should be at least ~1MB

        // Verify CR3 signature: bytes 4-7 should be "ftyp"
        String ftyp = new String(cr3Data, 4, 4);
        assertThat(ftyp).isEqualTo("ftyp");
        log.info("✓ Downloaded {} bytes, CR3 ftyp signature verified", cr3Data.length);

        // Save to test resources
        Path outputFile = SAMPLES_DIR.resolve("sample.cr3");
        Files.write(outputFile, cr3Data);
        log.info("✓ Saved CR3 to {}", outputFile.toAbsolutePath());

        // Also download and save the embedded JPEG preview
        String embeddedUrl = capturedCr3Url + "?kind=embedded";
        HttpResponse<byte[]> embeddedResp = httpClient.send(
                HttpRequest.newBuilder().uri(URI.create(embeddedUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (embeddedResp.statusCode() == 200) {
            byte[] jpegData = embeddedResp.body();
            Path jpegFile = SAMPLES_DIR.resolve("sample-embedded.jpg");
            Files.write(jpegFile, jpegData);
            log.info("✓ Saved embedded JPEG preview ({} bytes) to {}",
                    jpegData.length, jpegFile.toAbsolutePath());
        }

        // Download and save the thumbnail
        String thumbUrl = capturedCr3Url + "?kind=thumbnail";
        HttpResponse<byte[]> thumbResp = httpClient.send(
                HttpRequest.newBuilder().uri(URI.create(thumbUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (thumbResp.statusCode() == 200) {
            byte[] thumbData = thumbResp.body();
            Path thumbFile = SAMPLES_DIR.resolve("sample-thumbnail.jpg");
            Files.write(thumbFile, thumbData);
            log.info("✓ Saved thumbnail ({} bytes) to {}",
                    thumbData.length, thumbFile.toAbsolutePath());
        }
    }

    @Test
    @Order(4)
    void restoreOriginalQuality() throws Exception {
        if (originalQuality != null && !originalQuality.toLowerCase().contains("raw")) {
            // Only restore if we actually changed it
            log.info("Restoring original image quality to '{}'...", originalQuality);
            HttpResponse<String> resp = httpPut(
                    CAMERA_BASE_URL + "/ccapi/ver100/shooting/settings/stillimagequality",
                    "{\"value\":\"" + originalQuality + "\"}");
            log.info("Restore quality response: {} {}", resp.statusCode(), resp.body());
        } else {
            log.info("Quality was already RAW — nothing to restore");
        }
    }

    @Test
    @Order(5)
    void verifySavedFilesExistAndHaveReasonableSize() throws Exception {
        Path sampleCr3 = SAMPLES_DIR.resolve("sample.cr3");
        assertThat(sampleCr3).exists();
        assertThat(Files.size(sampleCr3)).isGreaterThan(1_000_000L);
        log.info("✓ sample.cr3 exists: {} bytes", Files.size(sampleCr3));

        Path embeddedJpeg = SAMPLES_DIR.resolve("sample-embedded.jpg");
        if (Files.exists(embeddedJpeg)) {
            assertThat(Files.size(embeddedJpeg)).isGreaterThan(1000L);
            log.info("✓ sample-embedded.jpg exists: {} bytes", Files.size(embeddedJpeg));
        }

        Path thumbnail = SAMPLES_DIR.resolve("sample-thumbnail.jpg");
        if (Files.exists(thumbnail)) {
            assertThat(Files.size(thumbnail)).isGreaterThan(100L);
            log.info("✓ sample-thumbnail.jpg exists: {} bytes", Files.size(thumbnail));
        }

        // Verify CR3 ftyp signature
        byte[] cr3Header = new byte[8];
        try (var in = Files.newInputStream(sampleCr3)) {
            assertThat(in.read(cr3Header)).isEqualTo(8);
        }
        assertThat(new String(cr3Header, 4, 4)).isEqualTo("ftyp");
        log.info("✓ sample.cr3 has valid ftyp signature");
    }

    /**
     * End-to-end parse test: reads the saved sample.cr3 with {@link Cr3Decoder},
     * asserts that camera model, all EXIF shooting settings (ISO, f-number,
     * exposure time, focal length, capture date/time) are populated, and that
     * decoding the PRVW preview produces a non-null image with the expected
     * dimensions. This test closes the gap between "file was downloaded" and
     * "the whole CR3 reading chain works".
     */
    @Test
    @Order(6)
    void parseSavedCr3AndVerifyMetadataAndPreview() throws Exception {
        Path sampleCr3 = SAMPLES_DIR.resolve("sample.cr3");
        assertThat(sampleCr3).as("sample.cr3 must exist (saved by @Order(3))").exists();

        File cr3File = sampleCr3.toFile();
        try (FileImageInputStream in = new FileImageInputStream(cr3File)) {
            Cr3Decoder decoder = new Cr3Decoder();
            decoder.setInput(in);

            // ── Container structure ─────────────────────────────────────────
            Cr3Container container = decoder.getContainer();
            assertThat(container).isNotNull();
            assertThat(container.ftypBox()).isNotNull();
            assertThat(container.moovBox()).isNotNull();
            assertThat(container.mdatBox()).isNotNull();
            assertThat(container.trackCount()).as("track count").isGreaterThanOrEqualTo(3);

            // ── Metadata (CMT1 + CMT2) ──────────────────────────────────────
            Cr3Metadata meta = decoder.getMetadata();
            assertThat(meta).isNotNull();

            // CMT1: camera model
            assertThat(meta.cameraModel())
                    .as("camera model should be read from CMT1")
                    .isNotBlank()
                    .containsIgnoringCase("Canon");

            // CMT2: ISO
            assertThat(meta.isoSpeed())
                    .as("ISO speed should be > 0 (from CMT2)")
                    .isGreaterThan(0);

            // CMT2: f-number
            assertThat(meta.fNumber())
                    .as("f-number should be > 0 (from CMT2)")
                    .isGreaterThan(0.0);

            // CMT2: exposure time
            assertThat(meta.exposureTime())
                    .as("exposure time should be > 0 (from CMT2)")
                    .isGreaterThan(0.0);

            // CMT2: focal length
            assertThat(meta.focalLengthMm())
                    .as("focal length should be > 0 mm (from CMT2)")
                    .isGreaterThan(0.0);

            // CMT2: capture date/time
            assertThat(meta.captureDateTime())
                    .as("capture date/time should not be empty (from CMT2)")
                    .isNotBlank();

            // Sensor dimensions from CRX track
            assertThat(meta.sensorWidth())
                    .as("sensor width should be > 0")
                    .isGreaterThan(0);
            assertThat(meta.sensorHeight())
                    .as("sensor height should be > 0")
                    .isGreaterThan(0);

            log.info("✓ Metadata: model='{}', sensor={}x{}, ISO={}, f/{}, {}s, {}mm, dt='{}'",
                    meta.cameraModel(), meta.sensorWidth(), meta.sensorHeight(),
                    meta.isoSpeed(), meta.fNumber(), meta.exposureTime(),
                    meta.focalLengthMm(), meta.captureDateTime());

            // ── Preview decoding ────────────────────────────────────────────
            BufferedImage preview = decoder.decode(Cr3DecodeOptions.defaults());
            assertThat(preview).as("PRVW preview image").isNotNull();
            assertThat(preview.getWidth())
                    .as("preview width should be > 0")
                    .isGreaterThan(0);
            assertThat(preview.getHeight())
                    .as("preview height should be > 0")
                    .isGreaterThan(0);

            log.info("✓ Preview decoded: {}x{}", preview.getWidth(), preview.getHeight());

            // ── Thumbnail decoding ──────────────────────────────────────────
            BufferedImage thumb = decoder.decode(
                    Cr3DecodeOptions.defaults().outputMode(Cr3DecodeOptions.OutputMode.THUMBNAIL));
            assertThat(thumb).as("THMB thumbnail image").isNotNull();
            assertThat(thumb.getWidth())
                    .as("thumbnail width should be > 0")
                    .isGreaterThan(0);

            log.info("✓ Thumbnail decoded: {}x{}", thumb.getWidth(), thumb.getHeight());
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
            log.debug("Could not stop live view (may already be off): {}", e.getMessage());
        }
    }

    private static void waitForCameraIdle() throws Exception {
        for (int i = 0; i < 60; i++) {
            try {
                HttpResponse<String> resp = httpGet(
                        CAMERA_BASE_URL + "/ccapi/ver100/contents/sd");
                if (resp.statusCode() == 200) {
                    log.info("Camera SD card accessible (waited {}ms)", i * 500);
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
        log.warn("Camera may still be busy after 30s — tests may fail");
    }

    /**
     * Searches the event/polling JSON response for a URL ending in .CR3.
     * Canon CCAPI JSON uses escaped forward slashes (\/), which are unescaped here.
     */
    private static String findCr3UrlInResponse(String json) {
        // Canon CCAPI escapes forward slashes as \/ in JSON — unescape first
        String unescaped = json.replace("\\/", "/");
        Pattern pattern = Pattern.compile("(http[^\"]+\\.(?i:cr3))");
        Matcher matcher = pattern.matcher(unescaped);
        String lastMatch = null;
        while (matcher.find()) {
            lastMatch = matcher.group(1);
        }
        return lastMatch;
    }

    /**
     * Browses the SD card via CCAPI to find the newest CR3 file.
     */
    private static String findNewestCr3OnSdCard() throws IOException, InterruptedException {
        String sdUrl = CAMERA_BASE_URL + "/ccapi/ver100/contents/sd";
        HttpResponse<String> sdResp = httpGet(sdUrl);
        if (sdResp.statusCode() != 200) {
            log.warn("Cannot list SD card: {}", sdResp.statusCode());
            return null;
        }

        // Find folder names — they look like "100CANON", "101CANON", etc.
        // Response JSON: {"dir":[{"name":"100CANON","attrib":"D",...},...]}
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
        log.info("Newest folder on SD: {}", lastFolder);

        // List files in the folder
        String folderUrl = sdUrl + "/" + lastFolder;
        HttpResponse<String> folderResp = httpGet(folderUrl);
        if (folderResp.statusCode() != 200) {
            log.warn("Cannot list folder {}: {}", lastFolder, folderResp.statusCode());
            return null;
        }

        // Find the last .CR3 file
        Pattern filePattern = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+\\.(?i:CR3))\"");
        Matcher fileMatcher = filePattern.matcher(folderResp.body());
        String lastCr3 = null;
        while (fileMatcher.find()) {
            lastCr3 = fileMatcher.group(1);
        }
        if (lastCr3 == null) {
            log.warn("No CR3 files found in {}", lastFolder);
            return null;
        }

        return folderUrl + "/" + lastCr3;
    }

    /**
     * Extracts a string value from a simple JSON object by key name.
     * Lightweight alternative to pulling in a JSON library.
     */
    private static String extractJsonStringValue(String json, String key) {
        Pattern pattern = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }
}
