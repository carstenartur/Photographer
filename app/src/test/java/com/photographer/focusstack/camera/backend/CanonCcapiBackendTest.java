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
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CanonCcapiBackendTest {

    private RestTemplate restTemplate;
    private CanonCcapiBackend backend;
    private static final String BASE_URL = "http://192.168.1.2:8080";

    @BeforeEach
    void setUp() {
        restTemplate = Mockito.mock(RestTemplate.class);
        backend = new CanonCcapiBackend(BASE_URL, restTemplate);
    }

    // ── getInfo ──────────────────────────────────────────────────────────────

    @Test
    void getInfo_returnsDeviceInfoFromCcapi() {
        when(restTemplate.getForObject(
                eq(BASE_URL + "/ccapi/ver100/deviceinformation"),
                eq(CanonCcapiBackend.DeviceInformation.class)))
                .thenReturn(new CanonCcapiBackend.DeviceInformation(
                        "Canon PowerShot G5 X Mark II", "SN12345", "1.1.0"));

        CameraInfo info = backend.getInfo();

        assertEquals("Canon PowerShot G5 X Mark II", info.model());
        assertEquals("ccapi-SN12345", info.id());
        assertEquals("1.1.0", info.firmwareVersion());
        assertEquals(BASE_URL, info.host());
    }

    @Test
    void getInfo_fallsBackToDefaultModelOnNetworkError() {
        when(restTemplate.getForObject(anyString(), eq(CanonCcapiBackend.DeviceInformation.class)))
                .thenThrow(new ResourceAccessException("Connection refused"));

        CameraInfo info = backend.getInfo();

        assertEquals(CanonCcapiBackend.DEFAULT_MODEL, info.model());
        assertNotNull(info.id());
    }

    @Test
    void getInfo_handlesNullFieldsFromCcapi() {
        when(restTemplate.getForObject(anyString(), eq(CanonCcapiBackend.DeviceInformation.class)))
                .thenReturn(new CanonCcapiBackend.DeviceInformation(null, null, null));

        CameraInfo info = backend.getInfo();

        assertEquals(CanonCcapiBackend.DEFAULT_MODEL, info.model());
        assertEquals("unknown", info.firmwareVersion());
    }

    // ── getLiveView ───────────────────────────────────────────────────────────

    @Test
    void getLiveView_returnsFrameFromCcapi() {
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
        when(restTemplate.getForObject(anyString(), eq(byte[].class))).thenReturn(null);

        LiveViewFrame frame = backend.getLiveView();

        assertNotNull(frame);
        assertEquals(0, frame.data().length);
    }

    // ── capture ───────────────────────────────────────────────────────────────

    @Test
    void capture_triggersShutterAndReturnsCaptureResult() {
        when(restTemplate.postForObject(anyString(), any(), eq(String.class)))
                .thenReturn("{\"message\": \"ok\"}");

        CaptureResult result = backend.capture();

        assertNotNull(result.imageId());
        assertTrue(result.fileName().startsWith("capture-"));
        assertTrue(result.fileName().endsWith(".jpg"));
        assertNotNull(result.capturedAt());
        // CCAPI stores images on the card; no inline data
        assertNull(result.imageData());
        verify(restTemplate).postForObject(
                eq(BASE_URL + "/ccapi/ver100/shooting/control/shutterbutton"),
                eq(java.util.Map.of("af", false)),
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
    void capture_throwsCameraExceptionOnNetworkError() {
        when(restTemplate.postForObject(anyString(), any(), eq(String.class)))
                .thenThrow(new ResourceAccessException("timeout"));

        assertThrows(CameraException.class, () -> backend.capture());
    }

    // ── setFocus ──────────────────────────────────────────────────────────────

    @Test
    void setFocus_sendsNear1ForSingleStepNear() {
        when(restTemplate.postForObject(anyString(), any(), eq(String.class))).thenReturn(null);

        backend.setFocus(new FocusCommand(FocusCommand.Direction.NEAR, 1));

        verify(restTemplate).postForObject(
                eq(BASE_URL + "/ccapi/ver100/shooting/control/mfdrive"),
                eq(java.util.Map.of("kind", "near1")),
                eq(String.class));
    }

    @Test
    void setFocus_sendsFar2ForMediumStepFar() {
        when(restTemplate.postForObject(anyString(), any(), eq(String.class))).thenReturn(null);

        backend.setFocus(new FocusCommand(FocusCommand.Direction.FAR, 3));

        verify(restTemplate).postForObject(
                eq(BASE_URL + "/ccapi/ver100/shooting/control/mfdrive"),
                eq(java.util.Map.of("kind", "far2")),
                eq(String.class));
    }

    @Test
    void setFocus_sendsFar3ForLargeStepFar() {
        when(restTemplate.postForObject(anyString(), any(), eq(String.class))).thenReturn(null);

        backend.setFocus(new FocusCommand(FocusCommand.Direction.FAR, 10));

        verify(restTemplate).postForObject(
                eq(BASE_URL + "/ccapi/ver100/shooting/control/mfdrive"),
                eq(java.util.Map.of("kind", "far3")),
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
    void getState_returnsBatteryAndStorageFromCcapi() {
        when(restTemplate.getForObject(
                eq(BASE_URL + "/ccapi/ver100/devicestatus/battery"),
                eq(CanonCcapiBackend.BatteryStatus.class)))
                .thenReturn(new CanonCcapiBackend.BatteryStatus("high"));
        when(restTemplate.getForObject(
                eq(BASE_URL + "/ccapi/ver100/devicestatus/storage"),
                eq(CanonCcapiBackend.StorageStatus.class)))
                .thenReturn(new CanonCcapiBackend.StorageStatus(2_000_000_000L));

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
                eq(BASE_URL + "/ccapi/ver100/devicestatus/storage"),
                eq(CanonCcapiBackend.StorageStatus.class)))
                .thenReturn(null);

        CameraState state = backend.getState();

        // Storage call succeeded (returned null without throwing) → connected=true
        assertTrue(state.connected());
        assertEquals(100, state.batteryLevel()); // default when battery fails
        assertEquals(0L, state.storageAvailable()); // null storage → default
    }

    @Test
    void getState_connectedFalseWhenBothEndpointsFail() {
        when(restTemplate.getForObject(
                eq(BASE_URL + "/ccapi/ver100/devicestatus/battery"),
                eq(CanonCcapiBackend.BatteryStatus.class)))
                .thenThrow(new ResourceAccessException("timeout"));
        when(restTemplate.getForObject(
                eq(BASE_URL + "/ccapi/ver100/devicestatus/storage"),
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
                .thenReturn(new CanonCcapiBackend.BatteryStatus("high"));
        when(restTemplate.getForObject(
                eq(BASE_URL + "/ccapi/ver100/devicestatus/storage"),
                eq(CanonCcapiBackend.StorageStatus.class)))
                .thenReturn(null);

        backend.setFocus(new FocusCommand(FocusCommand.Direction.FAR, 7));
        CameraState state = backend.getState();

        assertEquals(7, state.currentFocusPosition());
    }

    // ── parseBatteryLevel ─────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({
        "high,   80",
        "middle, 50",
        "low,    20",
        "empty,   0",
        "75,     75",
        "100,   100",
        "'high ',  80",   // trailing whitespace
        "' HIGH ', 80",  // leading/trailing whitespace + uppercase
        "150,   100",    // clamped to 100
        "-5,      0",    // clamped to 0
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

    // ── supports ─────────────────────────────────────────────────────────────

    @Test
    void supports_liveViewAndCaptureAndFocusAndSettingsRead() {
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
}
