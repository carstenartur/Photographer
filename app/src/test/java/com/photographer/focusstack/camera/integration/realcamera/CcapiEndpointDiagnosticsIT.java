package com.photographer.focusstack.camera.integration.realcamera;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Diagnostic tests that dump the raw CCAPI responses from a real camera.
 *
 * <p>These are <strong>not</strong> behavioural assertions — they exist to
 * investigate what the camera actually returns so that the
 * {@link com.photographer.focusstack.camera.backend.CanonCcapiBackend} can be
 * improved or debugged.
 *
 * <p>Run with:
 * <pre>
 *   mvn verify -pl app -am -Djava.awt.headless=true \
 *       -DexcludedGroups= -Dgroups=real-camera
 * </pre>
 */
@RealCamera
class CcapiEndpointDiagnosticsIT {

    private static final Logger log = LoggerFactory.getLogger(CcapiEndpointDiagnosticsIT.class);
    private static final String BASE_URL = CanonCcapiRealCameraIT.CAMERA_BASE_URL;

    private static RestTemplate rest;
    private static final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void setUp() {
        rest = new RestTemplate();
    }

    // ── Root discovery ──────────────────────────────────────────────────────

    @Test
    void dumpCcapiRootResponse() throws Exception {
        String url = BASE_URL + "/ccapi";
        log.info("GET {}", url);
        ResponseEntity<String> response = rest.getForEntity(url, String.class);

        log.info("Status: {}", response.getStatusCode());
        log.info("Body:\n{}", prettyPrint(response.getBody()));

        assertEquals(200, response.getStatusCode().value(),
                "CCAPI root should return 200");
        assertNotNull(response.getBody());
    }

    @Test
    @SuppressWarnings("unchecked")
    void dumpAllDiscoveredEndpoints() throws Exception {
        // Step 1: discover version roots
        Map<String, Object> versions = rest.getForObject(BASE_URL + "/ccapi", Map.class);
        assertNotNull(versions, "CCAPI root must return versions");
        log.info("Available versions: {}", versions.keySet());

        // Step 2: pick best version
        String bestVersion = versions.keySet().stream()
                .filter(k -> k.startsWith("ver"))
                .sorted()
                .reduce((a, b) -> b)
                .orElse(null);
        assertNotNull(bestVersion, "At least one CCAPI version should exist");

        // The CCAPI root returns the version value as a list of endpoint
        // descriptor objects: [{url: "...", get: true, post: false, ...}, ...]
        // NOT as a simple version-root URL.
        Object versionValue = versions.get(bestVersion);
        assertInstanceOf(List.class, versionValue,
                "Version value should be a list of endpoint descriptors");

        List<?> endpointDescriptors = (List<?>) versionValue;
        assertFalse(endpointDescriptors.isEmpty(), "Endpoint list should not be empty");

        log.info("Discovered {} endpoints under {}:", endpointDescriptors.size(), bestVersion);
        for (Object ep : endpointDescriptors) {
            if (ep instanceof Map<?, ?> epMap) {
                String url = String.valueOf(epMap.get("url"));
                boolean get = Boolean.TRUE.equals(epMap.get("get"));
                boolean post = Boolean.TRUE.equals(epMap.get("post"));
                boolean put = Boolean.TRUE.equals(epMap.get("put"));
                boolean delete = Boolean.TRUE.equals(epMap.get("delete"));
                log.info("  {} [GET={} POST={} PUT={} DELETE={}]",
                        url, get, post, put, delete);
            } else {
                log.info("  {}", ep);
            }
        }
    }

    // ── Device information ──────────────────────────────────────────────────

    @Test
    void dumpDeviceInformation() throws Exception {
        String url = BASE_URL + "/ccapi/ver100/deviceinformation";
        log.info("GET {}", url);
        try {
            ResponseEntity<String> response = rest.getForEntity(url, String.class);
            log.info("Status: {}", response.getStatusCode());
            log.info("Device info:\n{}", prettyPrint(response.getBody()));
        } catch (RestClientException e) {
            log.warn("deviceinformation not available: {}", e.getMessage());
        }
    }

    // ── Battery ─────────────────────────────────────────────────────────────

    @Test
    void dumpBatteryStatus() throws Exception {
        String url = BASE_URL + "/ccapi/ver100/devicestatus/battery";
        log.info("GET {}", url);
        try {
            ResponseEntity<String> response = rest.getForEntity(url, String.class);
            log.info("Status: {}", response.getStatusCode());
            log.info("Battery:\n{}", prettyPrint(response.getBody()));
        } catch (RestClientException e) {
            log.warn("Battery status not available: {}", e.getMessage());
        }
    }

    // ── Storage ─────────────────────────────────────────────────────────────

    @Test
    void dumpStorageStatus() throws Exception {
        tryGetAndDump("/ccapi/ver100/devicestatus/currentstorage", "Current storage");
        tryGetAndDump("/ccapi/ver100/devicestatus/storage", "Storage (fallback)");
    }

    // ── Live view ───────────────────────────────────────────────────────────

    @Test
    void dumpLiveViewFlipResponse() {
        String url = BASE_URL + "/ccapi/ver100/shooting/liveview/flip";
        log.info("GET {} (expecting JPEG bytes)", url);
        try {
            byte[] data = rest.getForObject(url, byte[].class);
            if (data != null) {
                log.info("Received {} bytes", data.length);
                if (data.length >= 3) {
                    log.info("First 3 bytes: {:02X} {:02X} {:02X}",
                            data[0] & 0xFF, data[1] & 0xFF, data[2] & 0xFF);
                }
            } else {
                log.warn("Received null response");
            }
        } catch (RestClientException e) {
            log.warn("liveview/flip not available: {}", e.getMessage());
        }
    }

    // ── Shooting settings ───────────────────────────────────────────────────

    @Test
    void dumpShootingSettings() throws Exception {
        tryGetAndDump("/ccapi/ver100/shooting/settings", "Shooting settings");
        tryGetAndDump("/ccapi/ver100/shooting/settings/tv", "Shutter speed (Tv)");
        tryGetAndDump("/ccapi/ver100/shooting/settings/av", "Aperture (Av)");
        tryGetAndDump("/ccapi/ver100/shooting/settings/iso", "ISO");
        tryGetAndDump("/ccapi/ver100/shooting/settings/wb", "White balance");
        tryGetAndDump("/ccapi/ver100/shooting/settings/metering", "Metering mode");
        tryGetAndDump("/ccapi/ver100/shooting/settings/afmethod", "AF method");
    }

    // ── Event polling ───────────────────────────────────────────────────────

    @Test
    void dumpEventPolling() throws Exception {
        tryGetAndDump("/ccapi/ver100/event/polling", "Event polling");
    }

    // ── AF endpoint HTTP method verification ─────────────────────────────────

    @Test
    void verifyAfEndpointAcceptsPost() {
        // Send a raw POST to the AF endpoint — this is what the fixed code does.
        // The camera should accept POST and drive the lens.
        String url = BASE_URL + "/ccapi/ver100/shooting/control/af";
        Map<String, Object> body = Map.of("action", "on", "lensdrivecomposite", "far_small");
        log.info("POST {} with body: {}", url, body);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<String> response = rest.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            log.info("POST AF — Status: {}, Body: {}", response.getStatusCode(), response.getBody());
            assertTrue(response.getStatusCode().is2xxSuccessful(),
                    "POST to AF endpoint should succeed, got: " + response.getStatusCode());
        } catch (RestClientException e) {
            fail("POST to AF endpoint should not throw: " + e.getMessage());
        }
    }

    @Test
    void verifyAfEndpointRejectsPut() {
        // Send a raw PUT to the AF endpoint — this is what the OLD broken code did.
        // The camera should reject it with 405 Method Not Allowed.
        String url = BASE_URL + "/ccapi/ver100/shooting/control/af";
        Map<String, Object> body = Map.of("action", "on", "lensdrivecomposite", "near_small");
        log.info("PUT {} with body: {}", url, body);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            rest.exchange(url, HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
            // If PUT succeeds, the camera firmware differs from what we observed.
            // Log it but don't fail — the important thing is POST works.
            log.warn("PUT to AF endpoint unexpectedly succeeded — camera may accept both methods");
        } catch (HttpClientErrorException.MethodNotAllowed e) {
            log.info("PUT correctly rejected with 405 Method Not Allowed");
        } catch (HttpClientErrorException e) {
            log.info("PUT rejected with HTTP {}: {}", e.getStatusCode(), e.getMessage());
        } catch (RestClientException e) {
            log.info("PUT rejected: {}", e.getMessage());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void verifyDescriptorMethodFlagsMatchRealBehaviour() {
        // Cross-check: the descriptor flags for the AF endpoint should say
        // post=true (and ideally put=false), matching the actual HTTP behaviour.
        Map<String, Object> versions = rest.getForObject(BASE_URL + "/ccapi", Map.class);
        assertNotNull(versions);

        String bestVersion = versions.keySet().stream()
                .filter(k -> k.startsWith("ver"))
                .sorted().reduce((a, b) -> b).orElse(null);
        assertNotNull(bestVersion);

        Object versionValue = versions.get(bestVersion);
        assertInstanceOf(List.class, versionValue);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> descriptors = (List<Map<String, Object>>) versionValue;
        Map<String, Object> afDescriptor = descriptors.stream()
                .filter(d -> String.valueOf(d.get("url")).contains("/shooting/control/af"))
                .findFirst()
                .orElse(null);

        if (afDescriptor != null) {
            boolean post = Boolean.TRUE.equals(afDescriptor.get("post"));
            boolean put = Boolean.TRUE.equals(afDescriptor.get("put"));
            log.info("AF descriptor: post={}, put={}, full={}", post, put, afDescriptor);
            assertTrue(post, "AF descriptor should indicate post=true");
            assertFalse(put, "AF descriptor should indicate put=false (or absent)");
        } else {
            log.warn("AF endpoint not found in descriptors — camera may not expose it");
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private void tryGetAndDump(String path, String label) {
        String url = BASE_URL + path;
        log.info("GET {} ({})", url, label);
        try {
            ResponseEntity<String> response = rest.getForEntity(url, String.class);
            log.info("{} — Status {}:\n{}", label, response.getStatusCode(),
                    prettyPrint(response.getBody()));
        } catch (RestClientException e) {
            log.warn("{} — Not available: {}", label, e.getMessage());
        }
    }

    private static String prettyPrint(String json) {
        if (json == null) return "<null>";
        try {
            Object parsed = mapper.readValue(json, Object.class);
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(parsed);
        } catch (Exception e) {
            return json; // not JSON, return as-is
        }
    }
}
