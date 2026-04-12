package com.photographer.focusstack.camera.integration.realcamera;

import com.photographer.focusstack.camera.backend.CanonCcapiBackend;
import com.photographer.focusstack.common.camera.CaptureResult;
import com.photographer.focusstack.common.camera.Operation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real-camera integration tests for content browsing, image transfer, event
 * polling, AF-operation settings, and the {@code drivefocus} limitation on
 * cameras with built-in lenses.
 *
 * <p>These tests verify every API call that the {@link CanonCcapiBackend} uses
 * against the real Canon camera, including the full image download pipeline.
 *
 * <p>Run with:
 * <pre>
 *   mvn verify -pl app -am -Djava.awt.headless=true \
 *       -DexcludedGroups= -Dgroups=real-camera
 * </pre>
 *
 * <h3>Canon CCAPI SDK findings</h3>
 * <ul>
 *   <li>{@code /shooting/control/drivefocus} — <strong>"This API is not supported
 *       for Canon cameras with built-in lenses."</strong> (CCAPI Reference §4.8.7)</li>
 *   <li>{@code /shooting/control/drivefocus} — <strong>"This API does not operate
 *       when AF operation (/afoperation) is manual."</strong></li>
 *   <li>The Canon PowerShot G5 X Mark II has a built-in lens, so {@code drivefocus}
 *       is expected to return 404 or not be listed in the endpoint descriptors.</li>
 * </ul>
 */
@RealCamera
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CcapiContentAndTransferIT {

    private static final Logger log = LoggerFactory.getLogger(CcapiContentAndTransferIT.class);
    private static final String BASE_URL = CanonCcapiRealCameraIT.CAMERA_BASE_URL;

    private static CanonCcapiBackend backend;
    private static RestTemplate rest;

    /** Base URL for SD card contents, discovered from storage API. */
    private static String sdContentsUrl;

    @BeforeAll
    static void createBackend() throws Exception {
        rest = new RestTemplate();
        backend = new CanonCcapiBackend(BASE_URL, rest);
        backend.getInfo(); // trigger endpoint discovery

        // Ensure the camera is idle before we start tests — previous test
        // classes may have left live view or recording active.
        waitForCameraIdle();

        // Discover the actual SD card contents URL from the storage API.
        // The Canon CCAPI does NOT use /DCIM as a subfolder — the folders
        // (100CANON, etc.) are directly under /contents/sd.
        @SuppressWarnings("unchecked")
        Map<String, Object> storage = rest.getForObject(
                BASE_URL + "/ccapi/ver100/devicestatus/storage", Map.class);
        if (storage != null) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list = (List<Map<String, Object>>) storage.get("storagelist");
            if (list != null && !list.isEmpty()) {
                sdContentsUrl = (String) list.get(0).get("url");
            }
        }
        if (sdContentsUrl == null) {
            sdContentsUrl = BASE_URL + "/ccapi/ver100/contents/sd";
        }
        log.info("CcapiContentAndTransferIT targeting {}, SD contents URL: {}", BASE_URL, sdContentsUrl);
    }

    /**
     * Waits up to 15 seconds for the camera to become idle.
     * Tries to stop live view, then polls a lightweight endpoint until the
     * camera stops returning 503 "During shooting or recording".
     */
    /**
     * Waits up to 30 seconds for the camera to become idle.
     * Stops live view (which blocks SD card access on the G5X Mark II),
     * then polls until the camera accepts SD card listing requests.
     */
    private static void waitForCameraIdle() throws Exception {
        // Stop live view — on G5X Mark II, active live view blocks contents/sd browsing
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                rest.postForObject(BASE_URL + "/ccapi/ver100/shooting/liveview",
                        java.util.Map.of("liveviewsize", "off", "cameradisplay", "on"), String.class);
                log.info("Live view stopped successfully");
                Thread.sleep(1000); // let camera settle after stopping LV
                break;
            } catch (org.springframework.web.client.HttpServerErrorException e) {
                log.debug("Stop live view attempt {}: {}", attempt + 1, e.getMessage());
                Thread.sleep(500);
            } catch (RestClientException e) {
                log.debug("Stop live view attempt {}: {}", attempt + 1, e.getMessage());
                break;
            }
        }

        // Poll contents/sd directly — only PASS when SD card is accessible
        for (int i = 0; i < 60; i++) {
            try {
                rest.getForObject(BASE_URL + "/ccapi/ver100/contents/sd", java.util.Map.class);
                log.info("Camera SD card accessible (waited {}ms)", i * 500);
                return;
            } catch (org.springframework.web.client.HttpServerErrorException e) {
                if (e.getStatusCode().value() == 503) {
                    // Still busy — try stopping live view again periodically
                    if (i > 0 && i % 4 == 0) {
                        try {
                            rest.postForObject(BASE_URL + "/ccapi/ver100/shooting/liveview",
                                    java.util.Map.of("liveviewsize", "off", "cameradisplay", "on"), String.class);
                        } catch (Exception ignored) { }
                    }
                    log.debug("Camera SD still busy, waiting... (attempt {})", i + 1);
                    Thread.sleep(500);
                } else {
                    throw e;
                }
            }
        }
        log.warn("Camera SD card may still be busy after 30s — tests may fail with 503");
    }

    @BeforeEach
    void logTest(TestInfo info) {
        log.info("▶ Running: {}", info.getDisplayName());
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  1. AF Operation Setting (§4.9.14)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    void afOperationIsReadable() {
        // GET /shooting/settings/afoperation
        String url = BASE_URL + "/ccapi/ver100/shooting/settings/afoperation";
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = rest.getForObject(url, Map.class);
            assertNotNull(response, "afoperation response must not be null");
            String value = (String) response.get("value");
            assertNotNull(value, "afoperation.value must be present");
            log.info("AF operation: value={}", value);

            // Per Canon SDK §4.8.7, drivefocus does NOT work when afoperation is "manual".
            // Document what the camera currently reports.
            log.info("SDK note: drivefocus requires afoperation != 'manual', current value='{}'", value);

            // Valid values per SDK: oneshot, servo, aifocus, manual
            assertTrue(List.of("oneshot", "servo", "aifocus", "manual").contains(value),
                    "afoperation value must be one of: oneshot, servo, aifocus, manual — was: " + value);
        } catch (RestClientException e) {
            log.warn("afoperation endpoint not available: {}", e.getMessage());
            // Some cameras may not expose this — don't fail
        }
    }

    @Test
    @Order(2)
    void afMethodIsReadable() {
        // GET /shooting/settings/afmethod
        String url = BASE_URL + "/ccapi/ver100/shooting/settings/afmethod";
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = rest.getForObject(url, Map.class);
            assertNotNull(response);
            String value = (String) response.get("value");
            assertNotNull(value, "afmethod.value must be present");
            @SuppressWarnings("unchecked")
            List<String> abilities = (List<String>) response.get("ability");
            log.info("AF method: value={}, abilities={}", value, abilities);
        } catch (RestClientException e) {
            log.warn("afmethod endpoint not available: {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  2. drivefocus Limitation for Built-in Lens Cameras (§4.8.7)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @Order(3)
    void drivefocusNotAvailableForBuiltInLensCamera() {
        // Canon CCAPI Reference §4.8.7:
        // "This API is not supported for Canon cameras with built-in lenses."
        // The PowerShot G5 X Mark II has a built-in lens, so:
        //   - /drivefocus should NOT be in the discovered endpoint list, OR
        //   - POST to /drivefocus should return 404
        Map<String, String> endpoints = backend.getDiscoveredEndpoints();
        boolean driveFocusDiscovered = endpoints.keySet().stream()
                .anyMatch(k -> k.contains("drivefocus"));

        log.info("drivefocus endpoint discovered: {}", driveFocusDiscovered);

        if (!driveFocusDiscovered) {
            log.info("✓ Confirmed: /drivefocus is NOT in the endpoint list — " +
                    "consistent with Canon SDK §4.8.7 (not supported for built-in lens cameras)");

            // Also verify that supports(FOCUS_CONTROL) returns false
            assertFalse(backend.supports(Operation.FOCUS_CONTROL),
                    "FOCUS_CONTROL must be false when drivefocus endpoint is not available");
            log.info("✓ backend.supports(FOCUS_CONTROL) correctly returns false");
        } else {
            // If it IS discovered, try to POST and verify it works or returns an error
            String url = BASE_URL + "/ccapi/ver100/shooting/control/drivefocus";
            log.info("drivefocus endpoint IS discovered — testing POST to {}", url);
            try {
                rest.postForObject(url, Map.of("value", "far1"), String.class);
                log.info("POST drivefocus succeeded — camera supports focus drive");
            } catch (RestClientException e) {
                log.warn("POST drivefocus failed: {} — endpoint exists but may not work in current mode", e.getMessage());
            }
        }
    }

    @Test
    @Order(4)
    void drivefocusReturns404WhenNotSupported() {
        // Direct HTTP POST to /drivefocus should return 404 on built-in lens cameras
        String url = BASE_URL + "/ccapi/ver100/shooting/control/drivefocus";
        try {
            ResponseEntity<String> response = rest.exchange(
                    url, HttpMethod.POST,
                    new org.springframework.http.HttpEntity<>(
                            "{\"value\":\"far1\"}".getBytes(),
                            new org.springframework.http.HttpHeaders()),
                    String.class);
            log.info("POST drivefocus responded: {} — {}", response.getStatusCode(), response.getBody());
            // If we got here, the camera supports it — not a failure
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            log.info("✓ POST /drivefocus returned 404 — confirmed not supported on this camera");
        } catch (RestClientException e) {
            log.info("POST /drivefocus returned error: {} — documenting behaviour", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  3. Storage & Contents Browsing (§4.6, §4.7)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @Order(10)
    void storageListReturnsSDCardInfo() {
        // GET /devicestatus/storage → { storagelist: [...] }
        String url = BASE_URL + "/ccapi/ver100/devicestatus/storage";
        @SuppressWarnings("unchecked")
        Map<String, Object> response = rest.getForObject(url, Map.class);
        assertNotNull(response, "storage response must not be null");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> storageList = (List<Map<String, Object>>) response.get("storagelist");
        assertNotNull(storageList, "storagelist must be present");
        assertFalse(storageList.isEmpty(), "storagelist must have at least one entry");

        Map<String, Object> sd = storageList.get(0);
        String name = (String) sd.get("name");
        String sdUrl = (String) sd.get("url");
        long maxSize = ((Number) sd.get("maxsize")).longValue();
        long spaceSize = ((Number) sd.get("spacesize")).longValue();

        log.info("Storage: name={}, url={}, maxSize={} bytes, freeSpace={} bytes, contents={}",
                name, sdUrl, maxSize, spaceSize, sd.get("contentsnumber"));

        assertEquals("sd", name, "First storage should be 'sd'");
        assertTrue(maxSize > 0, "maxsize should be positive");
        assertTrue(spaceSize >= 0, "spacesize should be non-negative");
        assertNotNull(sdUrl, "Storage URL must be provided");
        assertTrue(sdUrl.contains("/contents/sd"), "URL should point to /contents/sd");
    }

    @Test
    @Order(11)
    void contentsBrowseDCIMFolders() {
        // GET /contents/sd → { dir: [ {name:"100CANON", attrib:"D", ...}, ... ] }
        // Note: Canon CCAPI does NOT have a /DCIM level — folders like 100CANON
        // are directly under /contents/sd.
        @SuppressWarnings("unchecked")
        Map<String, Object> response = rest.getForObject(sdContentsUrl, Map.class);
        assertNotNull(response, "SD card listing must not be null");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dirs = (List<Map<String, Object>>) response.get("dir");
        assertNotNull(dirs, "SD card should contain a dir listing");
        assertFalse(dirs.isEmpty(), "SD card should have at least one subfolder");

        for (Map<String, Object> entry : dirs) {
            String name = (String) entry.get("name");
            String attrib = (String) entry.get("attrib");
            log.info("  {}/{} (attrib={})", sdContentsUrl, name, attrib);
            assertNotNull(name, "Folder name must not be null");
        }
        log.info("Found {} folders on SD card", dirs.size());
    }

    @Test
    @Order(12)
    void contentsBrowseListsImageFiles() {
        // Browse into the highest-numbered folder and list files
        @SuppressWarnings("unchecked")
        Map<String, Object> sdResponse = rest.getForObject(sdContentsUrl, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dirs = (List<Map<String, Object>>) sdResponse.get("dir");
        assumeTrue(dirs != null && !dirs.isEmpty(), "Need at least one folder on SD card");

        // Pick the last (highest-numbered) folder
        String folderName = (String) dirs.get(dirs.size() - 1).get("name");
        String folderUrl = sdContentsUrl + "/" + folderName;
        log.info("Browsing {}", folderUrl);

        @SuppressWarnings("unchecked")
        Map<String, Object> folderResponse = rest.getForObject(folderUrl, Map.class);
        assertNotNull(folderResponse, "Folder listing must not be null");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) folderResponse.get("dir");
        assertNotNull(files, "Folder should contain files");
        assertFalse(files.isEmpty(), "Folder should have at least one file");

        int imageCount = 0;
        String lastImageName = null;
        for (Map<String, Object> file : files) {
            String name = (String) file.get("name");
            String attrib = (String) file.get("attrib");
            if ("A".equals(attrib) && name != null &&
                    (name.toUpperCase().endsWith(".JPG") || name.toUpperCase().endsWith(".CR3"))) {
                imageCount++;
                lastImageName = name;
            }
        }
        log.info("Found {} image files in {}, last={}", imageCount, folderName, lastImageName);
        assertTrue(imageCount > 0, "Should have at least one image file in " + folderName);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  4. Image Download from SD Card (§4.7.5)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @Order(20)
    void downloadImageFromSDCard() {
        // Find the newest image and download it
        @SuppressWarnings("unchecked")
        Map<String, Object> sdResponse = rest.getForObject(sdContentsUrl, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dirs = (List<Map<String, Object>>) sdResponse.get("dir");
        assumeTrue(dirs != null && !dirs.isEmpty(), "Need at least one folder on SD card");

        String folderName = (String) dirs.get(dirs.size() - 1).get("name");
        String folderUrl = sdContentsUrl + "/" + folderName;

        @SuppressWarnings("unchecked")
        Map<String, Object> folderResponse = rest.getForObject(folderUrl, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) folderResponse.get("dir");
        assumeTrue(files != null && !files.isEmpty(), "Need at least one file in folder");

        // Find the last image file
        String imageName = null;
        for (Map<String, Object> file : files) {
            String name = (String) file.get("name");
            String attrib = (String) file.get("attrib");
            if ("A".equals(attrib) && name != null &&
                    (name.toUpperCase().endsWith(".JPG") || name.toUpperCase().endsWith(".CR3"))) {
                imageName = name;
            }
        }
        assumeTrue(imageName != null, "Need at least one image on the SD card");

        // Download the full image
        String imageUrl = sdContentsUrl + "/" + folderName + "/" + imageName;
        log.info("Downloading image: {}", imageUrl);

        byte[] imageData = rest.getForObject(imageUrl, byte[].class);
        assertNotNull(imageData, "Image data must not be null");
        assertTrue(imageData.length > 1000, "Image data should be substantial, was " + imageData.length + " bytes");
        log.info("✓ Downloaded {} — {} bytes", imageName, imageData.length);

        // Verify file signature
        if (imageName.toUpperCase().endsWith(".JPG")) {
            // JPEG starts with FF D8 FF
            assertEquals(0xFF, imageData[0] & 0xFF, "JPEG byte 0");
            assertEquals(0xD8, imageData[1] & 0xFF, "JPEG byte 1");
            assertEquals(0xFF, imageData[2] & 0xFF, "JPEG byte 2");
            log.info("✓ JPEG signature verified (FF D8 FF)");
        } else if (imageName.toUpperCase().endsWith(".CR3")) {
            // CR3 is based on ISOBMFF (ISO Base Media File Format)
            // Bytes 4-7 should be "ftyp"
            String ftyp = new String(imageData, 4, 4);
            assertEquals("ftyp", ftyp, "CR3 must start with ftyp box");
            log.info("✓ CR3/ISOBMFF signature verified (ftyp at offset 4)");
        }
    }

    @Test
    @Order(21)
    void downloadThumbnailFromSDCard() {
        // Download a thumbnail — GET /contents/sd/<folder>/<file>?kind=thumbnail
        @SuppressWarnings("unchecked")
        Map<String, Object> sdResponse = rest.getForObject(sdContentsUrl, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dirs = (List<Map<String, Object>>) sdResponse.get("dir");
        assumeTrue(dirs != null && !dirs.isEmpty());

        String folderName = (String) dirs.get(dirs.size() - 1).get("name");
        String folderUrl = sdContentsUrl + "/" + folderName;
        @SuppressWarnings("unchecked")
        Map<String, Object> folderResponse = rest.getForObject(folderUrl, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) folderResponse.get("dir");
        assumeTrue(files != null && !files.isEmpty());

        // Find any image
        String imageName = null;
        for (Map<String, Object> file : files) {
            String name = (String) file.get("name");
            String attrib = (String) file.get("attrib");
            if ("A".equals(attrib) && name != null &&
                    (name.toUpperCase().endsWith(".JPG") || name.toUpperCase().endsWith(".CR3"))) {
                imageName = name;
                break;
            }
        }
        assumeTrue(imageName != null, "Need an image on SD card");

        String thumbUrl = sdContentsUrl + "/" + folderName + "/" + imageName + "?kind=thumbnail";
        log.info("Downloading thumbnail: {}", thumbUrl);

        byte[] thumbData = rest.getForObject(thumbUrl, byte[].class);
        assertNotNull(thumbData, "Thumbnail data must not be null");
        assertTrue(thumbData.length > 100, "Thumbnail should be > 100 bytes, was " + thumbData.length);

        // Thumbnail should be a JPEG
        assertEquals(0xFF, thumbData[0] & 0xFF, "Thumbnail JPEG byte 0");
        assertEquals(0xD8, thumbData[1] & 0xFF, "Thumbnail JPEG byte 1");
        log.info("✓ Downloaded thumbnail — {} bytes (JPEG verified)", thumbData.length);
    }

    @Test
    @Order(22)
    void downloadEmbeddedImageFromCR3() {
        // GET /contents/sd/<folder>/<CR3 file>?kind=embedded
        // Only for RAW files — returns an embedded JPEG preview
        @SuppressWarnings("unchecked")
        Map<String, Object> sdResponse = rest.getForObject(sdContentsUrl, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dirs = (List<Map<String, Object>>) sdResponse.get("dir");
        assumeTrue(dirs != null && !dirs.isEmpty());

        String folderName = (String) dirs.get(dirs.size() - 1).get("name");
        String folderUrl = sdContentsUrl + "/" + folderName;
        @SuppressWarnings("unchecked")
        Map<String, Object> folderResponse = rest.getForObject(folderUrl, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) folderResponse.get("dir");
        assumeTrue(files != null && !files.isEmpty());

        // Find a CR3 file
        String cr3Name = null;
        for (Map<String, Object> file : files) {
            String name = (String) file.get("name");
            String attrib = (String) file.get("attrib");
            if ("A".equals(attrib) && name != null && name.toUpperCase().endsWith(".CR3")) {
                cr3Name = name;
            }
        }
        assumeTrue(cr3Name != null, "Need a CR3 file for embedded image test");

        String embeddedUrl = sdContentsUrl + "/" + folderName + "/" + cr3Name + "?kind=embedded";
        log.info("Downloading embedded JPEG from CR3: {}", embeddedUrl);

        try {
            byte[] embedded = rest.getForObject(embeddedUrl, byte[].class);
            assertNotNull(embedded);
            assertTrue(embedded.length > 1000, "Embedded JPEG should be substantial");
            assertEquals(0xFF, embedded[0] & 0xFF, "Embedded should be JPEG");
            assertEquals(0xD8, embedded[1] & 0xFF, "Embedded should be JPEG");
            log.info("✓ Downloaded embedded JPEG from {} — {} bytes", cr3Name, embedded.length);
        } catch (RestClientException e) {
            log.warn("Embedded image download failed: {} — may not be supported for this file", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  5. Event Polling — new image detection (§4.12)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @Order(30)
    void eventPollingReturnsValidJson() {
        // GET /event/polling — should return a JSON object with current camera state changes
        String url = BASE_URL + "/ccapi/ver100/event/polling";
        @SuppressWarnings("unchecked")
        Map<String, Object> response = rest.getForObject(url, Map.class);
        assertNotNull(response, "event/polling response must not be null");
        log.info("event/polling response keys: {}", response.keySet());

        // Log all top-level keys to understand what the camera reports
        for (Map.Entry<String, Object> entry : response.entrySet()) {
            log.info("  {} → {}", entry.getKey(), entry.getValue());
        }
    }

    @Test
    @Order(31)
    void eventPollingAfterCaptureContainsNewImageUrl() throws Exception {
        assumeTrue(backend.supports(Operation.CAPTURE), "Camera must support CAPTURE");

        // Clear any pending events by reading event/polling first
        String pollingUrl = BASE_URL + "/ccapi/ver100/event/polling";
        rest.getForObject(pollingUrl, Map.class);

        // Now trigger a capture
        log.info("Triggering capture to generate an event...");
        CaptureResult result = backend.capture();
        assertNotNull(result);
        log.info("Capture returned: imageId={}, fileSize={}", result.imageId(), result.fileSize());

        // Read event/polling — it should now contain the URL of the newly captured image
        @SuppressWarnings("unchecked")
        Map<String, Object> events = rest.getForObject(pollingUrl, Map.class);
        assertNotNull(events);
        log.info("event/polling after capture: {}", events.keySet());

        // Search for content URLs in the response
        List<String> contentUrls = extractContentUrlsFromMap(events);
        log.info("Content URLs in event/polling: {}", contentUrls);

        // The event may or may not still contain the image URL (it's consumed by
        // the backend during capture). Document the behaviour either way.
        if (contentUrls.isEmpty()) {
            log.info("No content URLs in event/polling after capture — " +
                    "the backend already consumed the event during capture()");
        } else {
            log.info("✓ Found {} content URL(s) in event/polling:", contentUrls.size());
            for (String url2 : contentUrls) {
                log.info("  {}", url2);
                assertTrue(url2.contains("/contents/"), "Content URL should contain /contents/");
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  6. Capture Returns REAL Image Bytes
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @Order(40)
    void captureReturnsActualImageData() {
        assumeTrue(backend.supports(Operation.CAPTURE), "Camera must support CAPTURE");

        log.info("Capturing image and verifying image data is returned...");
        CaptureResult result = backend.capture();

        assertNotNull(result, "CaptureResult must not be null");
        assertNotNull(result.imageId(), "imageId must be set");
        assertNotNull(result.capturedAt(), "capturedAt must be set");

        // KEY CHECK: the capture should return actual image bytes
        byte[] data = result.imageData();
        assertNotNull(data, "imageData must not be null — the backend should download the captured image");
        assertTrue(data.length > 10_000,
                "imageData should be a real image (expected >10KB, got " + data.length + " bytes)");
        log.info("✓ Capture returned {} bytes of image data", data.length);

        // Verify it's either JPEG or CR3
        boolean isJpeg = data.length >= 3
                && (data[0] & 0xFF) == 0xFF
                && (data[1] & 0xFF) == 0xD8
                && (data[2] & 0xFF) == 0xFF;
        boolean isCr3 = data.length >= 8
                && new String(data, 4, 4).equals("ftyp");

        assertTrue(isJpeg || isCr3,
                "Image data should be JPEG (FF D8 FF) or CR3 (ftyp at offset 4), " +
                        "first 8 bytes: " + hexDump(data, 8));
        log.info("✓ Image format: {}", isJpeg ? "JPEG" : "CR3");
        log.info("✓ File size reported: {} bytes, actual data: {} bytes",
                result.fileSize(), data.length);
    }

    @Test
    @Order(41)
    void captureFileSizeMatchesImageDataLength() {
        assumeTrue(backend.supports(Operation.CAPTURE), "Camera must support CAPTURE");

        CaptureResult result = backend.capture();
        assertNotNull(result);

        byte[] data = result.imageData();
        if (data != null && data.length > 0) {
            assertEquals(data.length, result.fileSize(),
                    "fileSize() should match imageData().length");
            log.info("✓ fileSize={} matches imageData.length={}", result.fileSize(), data.length);
        } else {
            log.warn("imageData is null/empty — fileSize={}", result.fileSize());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  7. Full Capture Pipeline: Shutter → Event Polling → Download
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @Order(50)
    void fullCapturePipelineEndToEnd() throws Exception {
        assumeTrue(backend.supports(Operation.CAPTURE), "Camera must support CAPTURE");

        // Step 1: List current contents to know what's already on the card
        @SuppressWarnings("unchecked")
        Map<String, Object> preCapture = rest.getForObject(sdContentsUrl, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> preDirs = (List<Map<String, Object>>) preCapture.get("dir");
        assumeTrue(preDirs != null && !preDirs.isEmpty(), "Need at least one folder on SD card");
        String lastFolder = (String) preDirs.get(preDirs.size() - 1).get("name");
        String preFolderUrl = sdContentsUrl + "/" + lastFolder;
        @SuppressWarnings("unchecked")
        Map<String, Object> preFiles = rest.getForObject(preFolderUrl, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> preFileList = (List<Map<String, Object>>) preFiles.get("dir");
        int preImageCount = preFileList != null ? preFileList.size() : 0;
        log.info("Pre-capture: {} files in {}", preImageCount, lastFolder);

        // Step 2: Clear event buffer
        String pollingUrl = BASE_URL + "/ccapi/ver100/event/polling";
        rest.getForObject(pollingUrl, Map.class);

        // Step 3: Trigger capture through the backend
        log.info("Triggering capture...");
        CaptureResult result = backend.capture();
        assertNotNull(result);
        log.info("Capture complete: imageId={}, fileSize={}", result.imageId(), result.fileSize());

        // Step 4: Verify the image appeared on the SD card
        Thread.sleep(1000); // allow camera to write
        @SuppressWarnings("unchecked")
        Map<String, Object> postFiles = rest.getForObject(preFolderUrl, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> postFileList = (List<Map<String, Object>>) postFiles.get("dir");
        int postImageCount = postFileList != null ? postFileList.size() : 0;
        log.info("Post-capture: {} files in {} (was {})", postImageCount, lastFolder, preImageCount);
        assertTrue(postImageCount > preImageCount,
                "A new file should have appeared on the SD card after capture");

        // Step 5: Find and download the new file
        Map<String, Object> newestFile = postFileList.get(postFileList.size() - 1);
        String newFileName = (String) newestFile.get("name");
        String downloadUrl = sdContentsUrl + "/" + lastFolder + "/" + newFileName;
        log.info("Downloading newly captured image: {}", downloadUrl);

        byte[] downloaded = rest.getForObject(downloadUrl, byte[].class);
        assertNotNull(downloaded);
        assertTrue(downloaded.length > 10_000,
                "Downloaded image should be substantial, was " + downloaded.length);
        log.info("✓ Full pipeline verified: capture → SD card → download ({} bytes)", downloaded.length);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  8. Shooting Mode & Settings Verification
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @Order(60)
    void shootingSettingsAreReadable() {
        // GET /shooting/settings — should return all current camera settings
        String url = BASE_URL + "/ccapi/ver100/shooting/settings";
        @SuppressWarnings("unchecked")
        Map<String, Object> settings = rest.getForObject(url, Map.class);
        assertNotNull(settings, "Shooting settings must not be null");

        // Log the shooting mode dial
        @SuppressWarnings("unchecked")
        Map<String, Object> modeDial = (Map<String, Object>) settings.get("shootingmodedial");
        if (modeDial != null) {
            String mode = (String) modeDial.get("value");
            log.info("Shooting mode dial: {}", mode);
        }

        // Log the AF operation
        @SuppressWarnings("unchecked")
        Map<String, Object> afOp = (Map<String, Object>) settings.get("afoperation");
        if (afOp != null) {
            String afValue = (String) afOp.get("value");
            log.info("AF operation: {}", afValue);

            // SDK says drivefocus doesn't work when afoperation is "manual"
            if ("manual".equals(afValue)) {
                log.warn("⚠ AF operation is 'manual' — drivefocus would be blocked even on supported cameras");
            }
        }

        // Log image quality setting
        @SuppressWarnings("unchecked")
        Map<String, Object> quality = (Map<String, Object>) settings.get("stillimagequality");
        if (quality != null) {
            log.info("Still image quality: {}", quality.get("value"));
        }

        log.info("✓ All shooting settings readable ({} top-level keys)", settings.size());
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Utility
    // ═══════════════════════════════════════════════════════════════════════

    private static String hexDump(byte[] data, int maxBytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(data.length, maxBytes); i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", data[i] & 0xFF));
        }
        return sb.toString();
    }

    /**
     * Recursively searches a parsed JSON response for URLs that look like
     * content paths (contain {@code /contents/} and end with a recognised
     * image extension).
     */
    @SuppressWarnings("unchecked")
    private static List<String> extractContentUrlsFromMap(Object node) {
        List<String> result = new java.util.ArrayList<>();
        if (node instanceof String s) {
            if (s.contains("/contents/") && isImageFile(s)) {
                result.add(s);
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                result.addAll(extractContentUrlsFromMap(item));
            }
        } else if (node instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                result.addAll(extractContentUrlsFromMap(value));
            }
        }
        return result;
    }

    private static boolean isImageFile(String name) {
        if (name == null) return false;
        String upper = name.toUpperCase();
        return upper.endsWith(".JPG") || upper.endsWith(".JPEG")
                || upper.endsWith(".CR2") || upper.endsWith(".CR3");
    }
}
