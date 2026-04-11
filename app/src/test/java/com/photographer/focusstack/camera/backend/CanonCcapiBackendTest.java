package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.camera.exception.CameraException;
import com.photographer.focusstack.common.camera.CameraInfo;
import com.photographer.focusstack.common.camera.CameraState;
import com.photographer.focusstack.common.camera.CaptureResult;
import com.photographer.focusstack.common.camera.FocusCommand;
import com.photographer.focusstack.common.camera.LiveViewFrame;
import com.photographer.focusstack.common.camera.Operation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;
import org.springframework.http.HttpEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CanonCcapiBackendTest {

    private RestTemplate restTemplate;
    private CanonCcapiBackend backend;
    private static final String BASE_URL = "http://192.168.1.2:8080";

    // Simulated CCAPI endpoint list returned by GET /ccapi/ver100
    private static final List<String> DISCOVERED_ENDPOINTS = List.of(
            "/ccapi/ver100/deviceinformation",
            "/ccapi/ver100/devicestatus/battery",
            "/ccapi/ver100/devicestatus/currentstorage",
            "/ccapi/ver100/shooting/liveview",
            "/ccapi/ver100/shooting/liveview/flip",
            "/ccapi/ver100/shooting/control/shutterbutton",
            "/ccapi/ver100/shooting/control/af",
            "/ccapi/ver100/event/polling",
            "/ccapi/ver100/contents/sd"
    );

    @BeforeEach
    void setUp() {
        restTemplate = Mockito.mock(RestTemplate.class);
        // Use 0 ms timeout so existing tests are fast (no image-download polling).
        backend = new CanonCcapiBackend(BASE_URL, restTemplate, 0, 0);

        // Mock the CCAPI discovery flow:
        // GET /ccapi → { "ver100": "/ccapi/ver100" }
        when(restTemplate.getForObject(eq(BASE_URL + "/ccapi"), eq(Map.class)))
                .thenReturn(Map.of("ver100", "/ccapi/ver100"));
        // GET /ccapi/ver100 → list of endpoint paths
        when(restTemplate.getForObject(eq(BASE_URL + "/ccapi/ver100"), eq(List.class)))
                .thenReturn(DISCOVERED_ENDPOINTS);
    }

    // ── endpoint discovery ────────────────────────────────────────────────────

    @Test
    void discoverEndpoints_parsesVersionsAndEndpoints() {
        backend.discoverEndpoints();

        assertNotNull(backend.findEndpoint("deviceinformation"));
        assertNotNull(backend.findEndpoint("liveview/flip"));
        assertNotNull(backend.findEndpoint("shutterbutton"));
        assertNotNull(backend.findEndpoint("/shooting/control/af"));
        assertNotNull(backend.findEndpoint("currentstorage"));
        assertNotNull(backend.findEndpoint("battery"));
    }

    @Test
    void discoverEndpoints_picksHighestVersion() {
        // Override with two versions — should pick ver110
        when(restTemplate.getForObject(eq(BASE_URL + "/ccapi"), eq(Map.class)))
                .thenReturn(Map.of("ver100", "/ccapi/ver100", "ver110", "/ccapi/ver110"));
        when(restTemplate.getForObject(eq(BASE_URL + "/ccapi/ver110"), eq(List.class)))
                .thenReturn(List.of("/ccapi/ver110/deviceinformation"));

        backend.discoverEndpoints();

        // Should have queried ver110, not ver100
        verify(restTemplate).getForObject(eq(BASE_URL + "/ccapi/ver110"), eq(List.class));
        assertNotNull(backend.findEndpoint("deviceinformation"));
    }

    @Test
    void discoverEndpoints_handlesArrayValuedVersionResponse() {
        // Some Canon firmwares return version URLs wrapped in a JSON array
        when(restTemplate.getForObject(eq(BASE_URL + "/ccapi"), eq(Map.class)))
                .thenReturn(Map.of("ver100", List.of("/ccapi/ver100")));
        when(restTemplate.getForObject(eq(BASE_URL + "/ccapi/ver100"), eq(List.class)))
                .thenReturn(DISCOVERED_ENDPOINTS);

        backend = new CanonCcapiBackend(BASE_URL, restTemplate);
        backend.discoverEndpoints();

        assertNotNull(backend.findEndpoint("deviceinformation"));
        assertNotNull(backend.findEndpoint("liveview/flip"));
        assertNotNull(backend.findEndpoint("shutterbutton"));
    }

    @Test
    void discoverEndpoints_handlesNetworkError() {
        when(restTemplate.getForObject(eq(BASE_URL + "/ccapi"), eq(Map.class)))
                .thenThrow(new ResourceAccessException("Connection refused"));

        // Should not throw
        assertDoesNotThrow(() -> backend.discoverEndpoints());
    }

    @Test
    void discoverEndpoints_handlesNullVersionResponse() {
        when(restTemplate.getForObject(eq(BASE_URL + "/ccapi"), eq(Map.class)))
                .thenReturn(null);

        assertDoesNotThrow(() -> backend.discoverEndpoints());
    }

    // ── getInfo ──────────────────────────────────────────────────────────────

    @Test
    void getInfo_returnsDeviceInfoFromDiscoveredEndpoint() {
        when(restTemplate.getForObject(
                eq(BASE_URL + "/ccapi/ver100/deviceinformation"),
                eq(CanonCcapiBackend.DeviceInformation.class)))
                .thenReturn(new CanonCcapiBackend.DeviceInformation(
                        "Canon PowerShot G5 X Mark II", "SN12345", "1.1.0", "AA:BB:CC:DD:EE:FF"));

        CameraInfo info = backend.getInfo();

        assertEquals("Canon PowerShot G5 X Mark II", info.model());
        assertEquals("ccapi-SN12345", info.id());
        assertEquals("1.1.0", info.firmwareVersion());
        assertEquals(BASE_URL, info.host());
    }

    @Test
    void getInfo_fallsBackToDefaultModelOnNetworkError() {
        when(restTemplate.getForObject(
                eq(BASE_URL + "/ccapi/ver100/deviceinformation"),
                eq(CanonCcapiBackend.DeviceInformation.class)))
                .thenThrow(new ResourceAccessException("Connection refused"));

        CameraInfo info = backend.getInfo();

        assertEquals(CanonCcapiBackend.DEFAULT_MODEL, info.model());
        assertNotNull(info.id());
    }

    @Test
    void getInfo_handlesNullFieldsFromCcapi() {
        when(restTemplate.getForObject(anyString(), eq(CanonCcapiBackend.DeviceInformation.class)))
                .thenReturn(new CanonCcapiBackend.DeviceInformation(null, null, null, null));

        CameraInfo info = backend.getInfo();

        assertEquals(CanonCcapiBackend.DEFAULT_MODEL, info.model());
        assertEquals("unknown", info.firmwareVersion());
    }

    // ── getLiveView ───────────────────────────────────────────────────────────

    @Test
    void getLiveView_returnsFrameFromDiscoveredFlipEndpoint() {
        byte[] fakeJpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, 0x00};
        when(restTemplate.getForObject(
                eq(BASE_URL + "/ccapi/ver100/shooting/liveview/flip"),
                eq(byte[].class)))
                .thenReturn(fakeJpeg);

        LiveViewFrame frame = backend.getLiveView();

        assertArrayEquals(fakeJpeg, frame.data());
        assertEquals("image/jpeg", frame.contentType());
        assertEquals(960, frame.width());
        assertEquals(640, frame.height());
    }

    @Test
    void getLiveView_throwsCameraExceptionOnNetworkError() {
        when(restTemplate.getForObject(anyString(), eq(byte[].class)))
                .thenThrow(new ResourceAccessException("timeout"));

        assertThrows(CameraException.class, () -> backend.getLiveView());
    }

    @Test
    void getLiveView_returnsEmptyFrameWhenCcapiReturnsNull() {
        when(restTemplate.getForObject(
                eq(BASE_URL + "/ccapi/ver100/shooting/liveview/flip"),
                eq(byte[].class)))
                .thenReturn(null);

        LiveViewFrame frame = backend.getLiveView();

        assertNotNull(frame);
        assertEquals(0, frame.data().length);
    }

    // ── capture ───────────────────────────────────────────────────────────────

    @Test
    void capture_triggersShutterViaDiscoveredEndpoint() {
        when(restTemplate.postForObject(anyString(), any(), eq(String.class)))
                .thenReturn("{\"message\": \"ok\"}");

        CaptureResult result = backend.capture();

        assertNotNull(result.imageId());
        assertTrue(result.fileName().startsWith("capture-"));
        assertTrue(result.fileName().endsWith(".jpg"));
        assertNotNull(result.capturedAt());
        assertNull(result.imageData());
        verify(restTemplate).postForObject(
                eq(BASE_URL + "/ccapi/ver100/shooting/control/shutterbutton"),
                argThat(arg -> arg instanceof HttpEntity<?> he && Map.of("af", false).equals(he.getBody())),
                eq(String.class));
    }

    @Test
    void capture_reportsFocusPositionFromLastSetFocus() {
        when(restTemplate.postForObject(anyString(), any(), eq(String.class))).thenReturn(null);

        backend.setFocus(new FocusCommand(FocusCommand.Direction.FAR, 5));
        CaptureResult result = backend.capture();

        assertEquals(5, result.focusPosition());
    }

    @Test
    void capture_throwsCameraExceptionOnNetworkError() {
        when(restTemplate.postForObject(anyString(), any(), eq(String.class)))
                .thenThrow(new ResourceAccessException("timeout"));

        assertThrows(CameraException.class, () -> backend.capture());
    }

    @Test
    void capture_downloadsNewImageAfterShutter() {
        // Backend with 0 ms poll interval and generous timeout so mock responds immediately.
        CanonCcapiBackend downloadBackend = new CanonCcapiBackend(BASE_URL, restTemplate, 0, 2_000);

        when(restTemplate.postForObject(anyString(), any(), eq(String.class))).thenReturn("{}");

        // Pre-capture: one existing image with an older datetime.
        CanonCcapiBackend.ContentListing folderListing = new CanonCcapiBackend.ContentListing(
                List.of(new CanonCcapiBackend.ContentEntry("100CANON", null, null, "D")));
        CanonCcapiBackend.ContentListing preCaptureFiles = new CanonCcapiBackend.ContentListing(
                List.of(new CanonCcapiBackend.ContentEntry(
                        "IMG_0001.JPG", 4_371_156L, "2024-04-01T11:15:03+09:00", "A")));
        CanonCcapiBackend.ContentListing postCaptureFiles = new CanonCcapiBackend.ContentListing(
                List.of(
                        new CanonCcapiBackend.ContentEntry(
                                "IMG_0001.JPG", 4_371_156L, "2024-04-01T11:15:03+09:00", "A"),
                        new CanonCcapiBackend.ContentEntry(
                                "IMG_0002.JPG", 4_450_000L, "2024-04-01T11:15:30+09:00", "A")));

        String dcimUrl = BASE_URL + "/ccapi/ver100/contents/sd/DCIM";
        String folderUrl = dcimUrl + "/100CANON";
        String imageUrl = folderUrl + "/IMG_0002.JPG";
        byte[] imageBytes = new byte[]{1, 2, 3, 4, 5};

        when(restTemplate.getForObject(eq(dcimUrl), eq(CanonCcapiBackend.ContentListing.class)))
                .thenReturn(folderListing);
        when(restTemplate.getForObject(eq(folderUrl), eq(CanonCcapiBackend.ContentListing.class)))
                .thenReturn(preCaptureFiles)   // first call: pre-capture snapshot
                .thenReturn(postCaptureFiles); // second call: post-capture poll
        when(restTemplate.getForObject(eq(imageUrl), eq(byte[].class)))
                .thenReturn(imageBytes);

        CaptureResult result = downloadBackend.capture();

        assertNotNull(result.imageData());
        assertArrayEquals(imageBytes, result.imageData());
        assertEquals(imageBytes.length, result.fileSize());
    }

    @Test
    void capture_downloadsFirstImageWhenNoPriorImageExists() {
        // Backend with 0 ms poll interval so the mock responds in the first loop iteration.
        CanonCcapiBackend downloadBackend = new CanonCcapiBackend(BASE_URL, restTemplate, 0, 2_000);

        when(restTemplate.postForObject(anyString(), any(), eq(String.class))).thenReturn("{}");

        // Pre-capture: SD card is empty (no files).
        CanonCcapiBackend.ContentListing emptyFolder = new CanonCcapiBackend.ContentListing(
                List.of(new CanonCcapiBackend.ContentEntry("100CANON", null, null, "D")));
        CanonCcapiBackend.ContentListing emptyFiles = new CanonCcapiBackend.ContentListing(List.of());
        CanonCcapiBackend.ContentListing postCaptureFiles = new CanonCcapiBackend.ContentListing(
                List.of(new CanonCcapiBackend.ContentEntry(
                        "IMG_0001.JPG", 4_371_156L, "2024-04-01T11:15:03+09:00", "A")));

        String dcimUrl = BASE_URL + "/ccapi/ver100/contents/sd/DCIM";
        String folderUrl = dcimUrl + "/100CANON";
        String imageUrl = folderUrl + "/IMG_0001.JPG";
        byte[] imageBytes = new byte[]{10, 20, 30};

        when(restTemplate.getForObject(eq(dcimUrl), eq(CanonCcapiBackend.ContentListing.class)))
                .thenReturn(emptyFolder);
        when(restTemplate.getForObject(eq(folderUrl), eq(CanonCcapiBackend.ContentListing.class)))
                .thenReturn(emptyFiles)        // pre-capture: no files
                .thenReturn(postCaptureFiles); // post-capture: new file appeared
        when(restTemplate.getForObject(eq(imageUrl), eq(byte[].class)))
                .thenReturn(imageBytes);

        CaptureResult result = downloadBackend.capture();

        assertNotNull(result.imageData());
        assertArrayEquals(imageBytes, result.imageData());
    }

    @Test
    void capture_returnsNullImageDataOnTimeout() {
        // Use a very short timeout and no mocked contents → download times out.
        CanonCcapiBackend timeoutBackend = new CanonCcapiBackend(BASE_URL, restTemplate, 0, 1);

        when(restTemplate.postForObject(anyString(), any(), eq(String.class))).thenReturn("{}");

        CaptureResult result = timeoutBackend.capture();

        assertNotNull(result.imageId());
        assertNull(result.imageData());
        assertEquals(0L, result.fileSize());
    }

    // ── setFocus ──────────────────────────────────────────────────────────────

    @Test
    void setFocus_usesAfEndpointWithSmallStep() {
        when(restTemplate.postForObject(anyString(), any(), eq(String.class))).thenReturn(null);

        backend.setFocus(new FocusCommand(FocusCommand.Direction.NEAR, 1));

        verify(restTemplate).postForObject(
                eq(BASE_URL + "/ccapi/ver100/shooting/control/af"),
                argThat(arg -> arg instanceof HttpEntity<?> he
                        && Map.of("action", "on", "lensdrivecomposite", "near_small").equals(he.getBody())),
                eq(String.class));
    }

    @Test
    void setFocus_usesAfEndpointWithMediumStep() {
        when(restTemplate.postForObject(anyString(), any(), eq(String.class))).thenReturn(null);

        backend.setFocus(new FocusCommand(FocusCommand.Direction.FAR, 3));

        verify(restTemplate).postForObject(
                eq(BASE_URL + "/ccapi/ver100/shooting/control/af"),
                argThat(arg -> arg instanceof HttpEntity<?> he
                        && Map.of("action", "on", "lensdrivecomposite", "far_medium").equals(he.getBody())),
                eq(String.class));
    }

    @Test
    void setFocus_usesAfEndpointWithLargeStep() {
        when(restTemplate.postForObject(anyString(), any(), eq(String.class))).thenReturn(null);

        backend.setFocus(new FocusCommand(FocusCommand.Direction.FAR, 10));

        verify(restTemplate).postForObject(
                eq(BASE_URL + "/ccapi/ver100/shooting/control/af"),
                argThat(arg -> arg instanceof HttpEntity<?> he
                        && Map.of("action", "on", "lensdrivecomposite", "far_large").equals(he.getBody())),
                eq(String.class));
    }

    @Test
    void setFocus_accumulatesFocusPositionAcrossMultipleCalls() {
        when(restTemplate.postForObject(anyString(), any(), eq(String.class))).thenReturn(null);

        backend.setFocus(new FocusCommand(FocusCommand.Direction.FAR, 10));
        backend.setFocus(new FocusCommand(FocusCommand.Direction.FAR, 5));
        backend.setFocus(new FocusCommand(FocusCommand.Direction.NEAR, 3));

        CaptureResult result = backend.capture();
        // 0 + 10 + 5 - 3 = 12
        assertEquals(12, result.focusPosition());
    }

    @Test
    void setFocus_fallsBackToRemoconWhenAfNotDiscovered() {
        // Override discovery: no /af endpoint, but has /remocon
        when(restTemplate.getForObject(eq(BASE_URL + "/ccapi/ver100"), eq(List.class)))
                .thenReturn(List.of(
                        "/ccapi/ver100/deviceinformation",
                        "/ccapi/ver100/shooting/liveview/remocon",
                        "/ccapi/ver100/shooting/liveview/flip"
                ));
        backend = new CanonCcapiBackend(BASE_URL, restTemplate);

        when(restTemplate.postForObject(anyString(), any(), eq(String.class))).thenReturn(null);

        backend.setFocus(new FocusCommand(FocusCommand.Direction.FAR, 3));

        verify(restTemplate).postForObject(
                eq(BASE_URL + "/ccapi/ver100/shooting/liveview/remocon"),
                argThat(arg -> arg instanceof HttpEntity<?> he
                        && Map.of("direction", "far", "magnitude", "medium").equals(he.getBody())),
                eq(String.class));
    }

    @Test
    void setFocus_throwsCameraExceptionOnNetworkError() {
        when(restTemplate.postForObject(anyString(), any(), eq(String.class)))
                .thenThrow(new ResourceAccessException("timeout"));

        assertThrows(CameraException.class,
                () -> backend.setFocus(new FocusCommand(FocusCommand.Direction.FAR, 1)));
    }

    // ── getState ──────────────────────────────────────────────────────────────

    @Test
    void getState_returnsBatteryAndStorageFromDiscoveredEndpoints() {
        when(restTemplate.getForObject(
                eq(BASE_URL + "/ccapi/ver100/devicestatus/battery"),
                eq(CanonCcapiBackend.BatteryStatus.class)))
                .thenReturn(new CanonCcapiBackend.BatteryStatus(null, null, "high", null));
        when(restTemplate.getForObject(
                eq(BASE_URL + "/ccapi/ver100/devicestatus/currentstorage"),
                eq(CanonCcapiBackend.StorageStatus.class)))
                .thenReturn(new CanonCcapiBackend.StorageStatus(null, null, 2_000_000_000L));

        CameraState state = backend.getState();

        assertTrue(state.connected());
        assertEquals(80, state.batteryLevel());
        assertEquals(2_000_000_000L, state.storageAvailable());
    }

    @Test
    void getState_usesDefaultsWhenBatteryEndpointFails() {
        when(restTemplate.getForObject(
                eq(BASE_URL + "/ccapi/ver100/devicestatus/battery"),
                eq(CanonCcapiBackend.BatteryStatus.class)))
                .thenThrow(new ResourceAccessException("timeout"));
        when(restTemplate.getForObject(
                eq(BASE_URL + "/ccapi/ver100/devicestatus/currentstorage"),
                eq(CanonCcapiBackend.StorageStatus.class)))
                .thenReturn(null);

        CameraState state = backend.getState();

        assertTrue(state.connected()); // storage call succeeded
        assertEquals(100, state.batteryLevel()); // default when battery fails
        assertEquals(0L, state.storageAvailable());
    }

    @Test
    void getState_connectedFalseWhenBothEndpointsFail() {
        when(restTemplate.getForObject(
                eq(BASE_URL + "/ccapi/ver100/devicestatus/battery"),
                eq(CanonCcapiBackend.BatteryStatus.class)))
                .thenThrow(new ResourceAccessException("timeout"));
        when(restTemplate.getForObject(
                eq(BASE_URL + "/ccapi/ver100/devicestatus/currentstorage"),
                eq(CanonCcapiBackend.StorageStatus.class)))
                .thenThrow(new ResourceAccessException("timeout"));

        CameraState state = backend.getState();

        assertFalse(state.connected());
    }

    @Test
    void getState_reportsFocusPositionFromLastSetFocus() {
        when(restTemplate.postForObject(anyString(), any(), eq(String.class))).thenReturn(null);
        when(restTemplate.getForObject(
                eq(BASE_URL + "/ccapi/ver100/devicestatus/battery"),
                eq(CanonCcapiBackend.BatteryStatus.class)))
                .thenReturn(new CanonCcapiBackend.BatteryStatus(null, null, "high", null));
        when(restTemplate.getForObject(
                eq(BASE_URL + "/ccapi/ver100/devicestatus/currentstorage"),
                eq(CanonCcapiBackend.StorageStatus.class)))
                .thenReturn(null);

        backend.setFocus(new FocusCommand(FocusCommand.Direction.FAR, 7));
        CameraState state = backend.getState();

        assertEquals(7, state.currentFocusPosition());
    }

    // ── parseBatteryLevel ─────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({
        "full,  100",
        "high,   80",
        "half,   50",
        "middle, 50",
        "low,    20",
        "empty,   0",
        "75,     75",
        "100,   100",
        "'high ',  80",
        "' HIGH ', 80",
        "150,   100",
        "-5,      0",
    })
    void parseBatteryLevel_mapsStringsCorrectly(String input, int expected) {
        assertEquals(expected, CanonCcapiBackend.parseBatteryLevel(input));
    }

    @Test
    void parseBatteryLevel_returnsDefaultForNullInput() {
        assertEquals(100, CanonCcapiBackend.parseBatteryLevel(null));
    }

    @Test
    void parseBatteryLevel_returnsDefaultForUnknownString() {
        assertEquals(100, CanonCcapiBackend.parseBatteryLevel("unknown"));
    }

    // ── toMfDriveSize ─────────────────────────────────────────────────────────

    @Test
    void toMfDriveSize_mapsStepCountsCorrectly() {
        assertEquals("small", CanonCcapiBackend.toMfDriveSize(1));
        assertEquals("medium", CanonCcapiBackend.toMfDriveSize(3));
        assertEquals("medium", CanonCcapiBackend.toMfDriveSize(5));
        assertEquals("large", CanonCcapiBackend.toMfDriveSize(10));
    }

    // ── supports ─────────────────────────────────────────────────────────────

    @Test
    void supports_reportsTrueForDiscoveredCapabilities() {
        // These are all in our DISCOVERED_ENDPOINTS list
        assertTrue(backend.supports(Operation.LIVE_VIEW));
        assertTrue(backend.supports(Operation.CAPTURE));
        assertTrue(backend.supports(Operation.FOCUS_CONTROL));
        assertTrue(backend.supports(Operation.SETTINGS_READ));
    }

    @Test
    void supports_zoomAndSettingsWriteNotSupported() {
        assertFalse(backend.supports(Operation.ZOOM_CONTROL));
        assertFalse(backend.supports(Operation.SETTINGS_WRITE));
    }

    @Test
    void supports_focusControlFalseWhenNeitherAfNorRemoconDiscovered() {
        // Override discovery: no /af and no /remocon
        when(restTemplate.getForObject(eq(BASE_URL + "/ccapi/ver100"), eq(List.class)))
                .thenReturn(List.of(
                        "/ccapi/ver100/deviceinformation",
                        "/ccapi/ver100/shooting/liveview/flip"
                ));
        backend = new CanonCcapiBackend(BASE_URL, restTemplate);

        assertFalse(backend.supports(Operation.FOCUS_CONTROL));
    }

    // ── extractString ─────────────────────────────────────────────────────────

    @Test
    void extractString_returnsStringDirectly() {
        assertEquals("/ccapi/ver100", CanonCcapiBackend.extractString("/ccapi/ver100"));
    }

    @Test
    void extractString_returnsFirstElementOfList() {
        assertEquals("/ccapi/ver100", CanonCcapiBackend.extractString(List.of("/ccapi/ver100")));
    }

    @Test
    void extractString_returnsNullForEmptyList() {
        assertNull(CanonCcapiBackend.extractString(List.of()));
    }

    @Test
    void extractString_returnsNullForNull() {
        assertNull(CanonCcapiBackend.extractString(null));
    }

    @Test
    void extractString_returnsNullForNonStringListElement() {
        assertNull(CanonCcapiBackend.extractString(List.of(42)));
    }

    // ── extractDescriptorUrls ────────────────────────────────────────────────

    @Test
    void extractDescriptorUrls_extractsUrlsFromDescriptorObjects() {
        List<Map<String, Object>> descriptors = List.of(
                Map.of("url", "/ccapi/ver100/deviceinformation", "get", true),
                Map.of("url", "/ccapi/ver100/devicestatus/battery", "get", true),
                Map.of("url", "/ccapi/ver100/shooting/liveview/flip", "get", true, "post", false)
        );
        List<String> result = CanonCcapiBackend.extractDescriptorUrls(descriptors);
        assertNotNull(result);
        assertEquals(3, result.size());
        assertEquals("/ccapi/ver100/deviceinformation", result.get(0));
        assertEquals("/ccapi/ver100/devicestatus/battery", result.get(1));
        assertEquals("/ccapi/ver100/shooting/liveview/flip", result.get(2));
    }

    @Test
    void extractDescriptorUrls_returnsNullForStringList() {
        // Format A: list of strings should NOT be matched
        assertNull(CanonCcapiBackend.extractDescriptorUrls(List.of("/ccapi/ver100")));
    }

    @Test
    void extractDescriptorUrls_returnsNullForEmptyList() {
        assertNull(CanonCcapiBackend.extractDescriptorUrls(List.of()));
    }

    @Test
    void extractDescriptorUrls_returnsNullForNull() {
        assertNull(CanonCcapiBackend.extractDescriptorUrls(null));
    }

    @Test
    void extractDescriptorUrls_returnsNullForString() {
        assertNull(CanonCcapiBackend.extractDescriptorUrls("/ccapi/ver100"));
    }

    @Test
    void extractDescriptorUrls_skipsDescriptorsWithoutUrl() {
        List<Map<String, Object>> descriptors = List.of(
                Map.of("url", "/ccapi/ver100/deviceinformation", "get", true),
                Map.of("get", true)  // no "url" key
        );
        List<String> result = CanonCcapiBackend.extractDescriptorUrls(descriptors);
        assertNotNull(result);
        assertEquals(1, result.size());
    }

    // ── discovery with descriptor-object format (Format B) ──────────────────

    @Test
    void discoverEndpoints_handlesDescriptorObjectFormat() {
        // Simulate real Canon camera: version value is a list of descriptor objects
        List<Map<String, Object>> descriptors = List.of(
                Map.of("url", "/ccapi/ver100/deviceinformation", "get", true),
                Map.of("url", "/ccapi/ver100/devicestatus/battery", "get", true),
                Map.of("url", "/ccapi/ver100/shooting/liveview/flip", "get", true),
                Map.of("url", "/ccapi/ver100/shooting/control/shutterbutton", "get", false, "post", true),
                Map.of("url", "/ccapi/ver100/shooting/control/af", "post", true)
        );
        when(restTemplate.getForObject(eq(BASE_URL + "/ccapi"), eq(Map.class)))
                .thenReturn(Map.of("ver100", descriptors));

        backend = new CanonCcapiBackend(BASE_URL, restTemplate);
        backend.discoverEndpoints();

        assertNotNull(backend.findEndpoint("deviceinformation"));
        assertNotNull(backend.findEndpoint("battery"));
        assertNotNull(backend.findEndpoint("liveview/flip"));
        assertNotNull(backend.findEndpoint("shutterbutton"));
        assertNotNull(backend.findEndpoint("/shooting/control/af"));

        // Should NOT have made a second GET call for the version root
        verify(restTemplate, never()).getForObject(eq(BASE_URL + "/ccapi/ver100"), eq(List.class));
    }

    @Test
    void setFocus_usesPutWhenDescriptorExplicitlyAllowsPut() {
        // Simulate a camera where the AF endpoint explicitly allows PUT
        List<Map<String, Object>> descriptors = List.of(
                Map.of("url", "/ccapi/ver100/deviceinformation", "get", true),
                Map.of("url", "/ccapi/ver100/shooting/control/af", "put", true)
        );
        when(restTemplate.getForObject(eq(BASE_URL + "/ccapi"), eq(Map.class)))
                .thenReturn(Map.of("ver100", descriptors));
        doNothing().when(restTemplate).put(anyString(), any());

        backend = new CanonCcapiBackend(BASE_URL, restTemplate);
        backend.setFocus(new FocusCommand(FocusCommand.Direction.FAR, 1));

        verify(restTemplate).put(
                eq(BASE_URL + "/ccapi/ver100/shooting/control/af"),
                argThat(arg -> arg instanceof HttpEntity<?> he
                        && Map.of("action", "on", "lensdrivecomposite", "far_small").equals(he.getBody())));
    }

    @Test
    void setFocus_usesPostWhenDescriptorAllowsPostNotPut() {
        // Simulate real Canon camera where the AF endpoint allows POST (not PUT)
        List<Map<String, Object>> descriptors = List.of(
                Map.of("url", "/ccapi/ver100/deviceinformation", "get", true),
                Map.of("url", "/ccapi/ver100/shooting/control/af", "post", true)
        );
        when(restTemplate.getForObject(eq(BASE_URL + "/ccapi"), eq(Map.class)))
                .thenReturn(Map.of("ver100", descriptors));
        when(restTemplate.postForObject(anyString(), any(), eq(String.class))).thenReturn(null);

        backend = new CanonCcapiBackend(BASE_URL, restTemplate);
        backend.setFocus(new FocusCommand(FocusCommand.Direction.NEAR, 3));

        verify(restTemplate).postForObject(
                eq(BASE_URL + "/ccapi/ver100/shooting/control/af"),
                argThat(arg -> arg instanceof HttpEntity<?> he
                        && Map.of("action", "on", "lensdrivecomposite", "near_medium").equals(he.getBody())),
                eq(String.class));
        // Should NOT have used PUT
        verify(restTemplate, never()).put(anyString(), any());
    }

    // ── getState with no storage endpoint ────────────────────────────────────

    @Test
    void getState_skipsStorageWhenEndpointNotDiscovered() {
        // Override discovery: no storage-related endpoints
        when(restTemplate.getForObject(eq(BASE_URL + "/ccapi/ver100"), eq(List.class)))
                .thenReturn(List.of(
                        "/ccapi/ver100/deviceinformation",
                        "/ccapi/ver100/devicestatus/battery"
                ));
        backend = new CanonCcapiBackend(BASE_URL, restTemplate, 0, 0);

        when(restTemplate.getForObject(
                eq(BASE_URL + "/ccapi/ver100/devicestatus/battery"),
                eq(CanonCcapiBackend.BatteryStatus.class)))
                .thenReturn(new CanonCcapiBackend.BatteryStatus(null, null, "high", null));

        CameraState state = backend.getState();

        assertTrue(state.connected());
        assertEquals(80, state.batteryLevel());
        assertEquals(0L, state.storageAvailable());
        // Should never have attempted a storage call
        verify(restTemplate, never()).getForObject(anyString(), eq(CanonCcapiBackend.StorageStatus.class));
    }

    // ── ContentListing DTO parsing ────────────────────────────────────────────

    @Test
    void contentListing_parsesDirectoryEntries() {
        CanonCcapiBackend.ContentListing listing = new CanonCcapiBackend.ContentListing(
                List.of(
                        new CanonCcapiBackend.ContentEntry("100CANON", null, null, "D"),
                        new CanonCcapiBackend.ContentEntry("101CANON", null, null, "D")));

        assertNotNull(listing.dir());
        assertEquals(2, listing.dir().size());
        assertEquals("100CANON", listing.dir().get(0).name());
        assertEquals("D", listing.dir().get(0).attrib());
    }

    @Test
    void contentListing_parsesFileEntries() {
        CanonCcapiBackend.ContentListing listing = new CanonCcapiBackend.ContentListing(
                List.of(
                        new CanonCcapiBackend.ContentEntry(
                                "IMG_0042.JPG", 4_371_156L, "2024-04-01T11:15:03+09:00", "A")));

        CanonCcapiBackend.ContentEntry entry = listing.dir().get(0);
        assertEquals("IMG_0042.JPG", entry.name());
        assertEquals(4_371_156L, entry.size());
        assertEquals("2024-04-01T11:15:03+09:00", entry.datetime());
        assertEquals("A", entry.attrib());
    }

    // ── isNewerThan ───────────────────────────────────────────────────────────

    @Test
    void isNewerThan_returnsTrueWhenFirstIsLater() {
        assertTrue(CanonCcapiBackend.isNewerThan(
                "2024-04-01T11:15:30+09:00",
                "2024-04-01T11:15:03+09:00"));
    }

    @Test
    void isNewerThan_returnsFalseWhenFirstIsEarlier() {
        assertFalse(CanonCcapiBackend.isNewerThan(
                "2024-04-01T11:15:03+09:00",
                "2024-04-01T11:15:30+09:00"));
    }

    @Test
    void isNewerThan_returnsFalseWhenFirstIsNull() {
        assertFalse(CanonCcapiBackend.isNewerThan(null, "2024-04-01T11:15:03+09:00"));
    }

    @Test
    void isNewerThan_returnsTrueWhenSecondIsNull() {
        assertTrue(CanonCcapiBackend.isNewerThan("2024-04-01T11:15:03+09:00", null));
    }

    @Test
    void isNewerThan_returnsFalseWhenBothNull() {
        assertFalse(CanonCcapiBackend.isNewerThan(null, null));
    }

    @Test
    void isNewerThan_fallsBackToLexicographicComparisonForUnparseableDatetimes() {
        // Strings without timezone offset are not valid OffsetDateTime values,
        // so the parser throws and the method falls back to lexicographic comparison.
        // ISO 8601 local datetimes still sort correctly as strings.
        assertTrue(CanonCcapiBackend.isNewerThan("2024-04-01T12:00:00", "2024-04-01T11:00:00"));
        assertFalse(CanonCcapiBackend.isNewerThan("2024-04-01T10:00:00", "2024-04-01T11:00:00"));
    }
}