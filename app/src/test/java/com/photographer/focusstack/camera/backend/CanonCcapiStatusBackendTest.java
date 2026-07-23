package com.photographer.focusstack.camera.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.photographer.focusstack.common.camera.CameraState;
import com.photographer.focusstack.common.device.BatteryInfo;
import com.photographer.focusstack.common.device.StorageInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CanonCcapiStatusBackendTest {

    private static final String BASE_URL = "http://192.168.1.2:8080";
    private static final String STORAGE_V100 = BASE_URL + "/ccapi/ver100/devicestatus/storage";
    private static final String STORAGE_V110 = BASE_URL + "/ccapi/ver110/devicestatus/storage";
    private static final String CURRENT_STORAGE_V110 = BASE_URL + "/ccapi/ver110/devicestatus/currentstorage";
    private static final String BATTERY_V100 = BASE_URL + "/ccapi/ver100/devicestatus/battery";
    private static final String BATTERY_LIST_V110 = BASE_URL + "/ccapi/ver110/devicestatus/batterylist";
    private static final String LIVE_VIEW_V110 = BASE_URL + "/ccapi/ver110/shooting/liveview";

    private RestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
    }

    @Test
    void mapsVersion110StoragePathsAndKeepsMultipleCardsDistinct() {
        when(restTemplate.getForObject(eq(BASE_URL + "/ccapi"), eq(Map.class)))
                .thenReturn(apiRootWithVersionedStatus());
        when(restTemplate.getForObject(
                eq(STORAGE_V110), eq(CanonCcapiStatusBackend.StorageListResponse.class)))
                .thenReturn(new CanonCcapiStatusBackend.StorageListResponse(List.of(
                        new CanonCcapiStatusBackend.StorageEntry(
                                "card1", null, "/ccapi/ver110/contents/card1",
                                "readwrite", 4_000L, 3_000L, 12L),
                        new CanonCcapiStatusBackend.StorageEntry(
                                "card2", null, "/ccapi/ver110/contents/card2",
                                "readonly", 8_000L, 5_000L, 20L)
                )));

        CanonCcapiStatusBackend backend = new CanonCcapiStatusBackend(BASE_URL, restTemplate);
        StorageInfo result = backend.getStorageInfo();

        assertEquals(2, result.volumes().size());
        assertEquals("card1", result.volumes().get(0).name());
        assertEquals("/ccapi/ver110/contents/card1", result.volumes().get(0).path());
        assertEquals("readwrite", result.volumes().get(0).accessibility());
        assertEquals("card2", result.volumes().get(1).name());
        assertEquals("readonly", result.volumes().get(1).accessibility());
        assertEquals(8_000L, result.totalFreeBytes());

        verify(restTemplate).getForObject(
                eq(STORAGE_V110), eq(CanonCcapiStatusBackend.StorageListResponse.class));
        verify(restTemplate, never()).getForObject(
                eq(CURRENT_STORAGE_V110), eq(CanonCcapiStatusBackend.StorageListResponse.class));
    }

    @Test
    void mapsVersion100AbsoluteStorageUrl() {
        when(restTemplate.getForObject(eq(BASE_URL + "/ccapi"), eq(Map.class)))
                .thenReturn(Map.of("ver100", List.of(descriptor(
                        "url", STORAGE_V100))));
        when(restTemplate.getForObject(
                eq(STORAGE_V100), eq(CanonCcapiStatusBackend.StorageListResponse.class)))
                .thenReturn(new CanonCcapiStatusBackend.StorageListResponse(List.of(
                        new CanonCcapiStatusBackend.StorageEntry(
                                "sd",
                                BASE_URL + "/ccapi/ver100/contents/sd",
                                null,
                                "readwrite",
                                4_016_570_368L,
                                3_800_924_160L,
                                511L)
                )));

        StorageInfo.Volume volume = new CanonCcapiStatusBackend(BASE_URL, restTemplate)
                .getStorageInfo().volumes().getFirst();

        assertEquals("sd", volume.name());
        assertEquals(BASE_URL + "/ccapi/ver100/contents/sd", volume.path());
        assertEquals(4_016_570_368L, volume.totalBytes());
        assertEquals(3_800_924_160L, volume.freeBytes());
        assertEquals(511, volume.contentCount());
    }

    @Test
    void successfulEmptyStorageListMeansReachableCameraButUnknownCapacity() {
        when(restTemplate.getForObject(eq(BASE_URL + "/ccapi"), eq(Map.class)))
                .thenReturn(Map.of("ver110", List.of(descriptor(
                        "path", "/ccapi/ver110/devicestatus/storage"))));
        when(restTemplate.getForObject(
                eq(STORAGE_V110), eq(CanonCcapiStatusBackend.StorageListResponse.class)))
                .thenReturn(new CanonCcapiStatusBackend.StorageListResponse(List.of()));

        CanonCcapiStatusBackend backend = new CanonCcapiStatusBackend(BASE_URL, restTemplate);
        CameraState state = backend.getState();

        assertTrue(state.connected());
        assertEquals(-1L, state.storageAvailable());
        assertTrue(backend.getStorageInfo().volumes().isEmpty());
    }

    @Test
    void networkFailureDoesNotBecomeZeroFreeBytes() {
        when(restTemplate.getForObject(eq(BASE_URL + "/ccapi"), eq(Map.class)))
                .thenReturn(Map.of("ver110", List.of(descriptor(
                        "path", "/ccapi/ver110/devicestatus/storage"))));
        when(restTemplate.getForObject(
                eq(STORAGE_V110), eq(CanonCcapiStatusBackend.StorageListResponse.class)))
                .thenThrow(new ResourceAccessException("timeout"));

        CameraState state = new CanonCcapiStatusBackend(BASE_URL, restTemplate).getState();

        assertFalse(state.connected());
        assertEquals(-1L, state.storageAvailable());
        assertEquals(-1, state.batteryLevel());
    }

    @Test
    void transientDiscoveryFailureIsRetriedInsteadOfCachedForever() {
        when(restTemplate.getForObject(eq(BASE_URL + "/ccapi"), eq(Map.class)))
                .thenThrow(new ResourceAccessException("temporary timeout"))
                .thenReturn(Map.of("ver100", List.of(descriptor("url", BATTERY_V100))));
        when(restTemplate.getForObject(
                eq(BATTERY_V100), eq(CanonCcapiStatusBackend.BatteryEntry.class)))
                .thenReturn(new CanonCcapiStatusBackend.BatteryEntry(
                        null, "LP-E6", "battery", "high", "good"));

        CanonCcapiStatusBackend backend = new CanonCcapiStatusBackend(BASE_URL, restTemplate);

        assertEquals(BatteryInfo.unknown(), backend.getBatteryInfo());
        assertEquals(80, backend.getBatteryInfo().percent());
        verify(restTemplate, times(2)).getForObject(eq(BASE_URL + "/ccapi"), eq(Map.class));
    }

    @Test
    void batteryListUsesWorstKnownBatteryAndPreservesDetails() {
        when(restTemplate.getForObject(eq(BASE_URL + "/ccapi"), eq(Map.class)))
                .thenReturn(Map.of("ver110", List.of(descriptor(
                        "path", "/ccapi/ver110/devicestatus/batterylist"))));
        when(restTemplate.getForObject(
                eq(BATTERY_LIST_V110), eq(CanonCcapiStatusBackend.BatteryListResponse.class)))
                .thenReturn(new CanonCcapiStatusBackend.BatteryListResponse(List.of(
                        new CanonCcapiStatusBackend.BatteryEntry(
                                "grip01", "LP-E6", "battery", "100", "good"),
                        new CanonCcapiStatusBackend.BatteryEntry(
                                "grip02", "LP-E6", "battery", "25", "normal")
                )));

        BatteryInfo battery = new CanonCcapiStatusBackend(BASE_URL, restTemplate).getBatteryInfo();

        assertEquals(25, battery.percent());
        assertEquals("25", battery.level());
        assertEquals("battery", battery.kind());
        assertEquals("normal", battery.quality());
    }

    @Test
    void unknownBatteryDoesNotBecomeOneHundredPercent() {
        when(restTemplate.getForObject(eq(BASE_URL + "/ccapi"), eq(Map.class)))
                .thenReturn(Map.of("ver100", List.of(descriptor(
                        "url", BATTERY_V100))));
        when(restTemplate.getForObject(
                eq(BATTERY_V100), eq(CanonCcapiStatusBackend.BatteryEntry.class)))
                .thenReturn(new CanonCcapiStatusBackend.BatteryEntry(
                        null, "unknown", "unknown", "unknown", "unknown"));

        CanonCcapiStatusBackend backend = new CanonCcapiStatusBackend(BASE_URL, restTemplate);
        BatteryInfo battery = backend.getBatteryInfo();
        CameraState state = backend.getState();

        assertEquals(-1, battery.percent());
        assertEquals(-1, state.batteryLevel());
        assertTrue(state.connected());
    }

    @Test
    void supportsQuarterAndRejectsOutOfRangeNumericBatteryValues() {
        assertEquals(25, CanonCcapiStatusBackend.strictBatteryPercent("quarter"));
        assertEquals(0, CanonCcapiStatusBackend.strictBatteryPercent("0"));
        assertEquals(100, CanonCcapiStatusBackend.strictBatteryPercent("100"));
        assertEquals(-1, CanonCcapiStatusBackend.strictBatteryPercent("101"));
        assertEquals(-1, CanonCcapiStatusBackend.strictBatteryPercent("unknown"));
    }

    @Test
    void usesDocumentedDeveloperTopUrlFallback() {
        when(restTemplate.getForObject(eq(BASE_URL + "/ccapi"), eq(Map.class)))
                .thenReturn(Map.of());
        when(restTemplate.getForObject(
                eq(BASE_URL + "/ccapi/ver100/topurlfordev"), eq(Map.class)))
                .thenReturn(Map.of("ver100", List.of(descriptor("url", STORAGE_V100))));
        when(restTemplate.getForObject(
                eq(STORAGE_V100), eq(CanonCcapiStatusBackend.StorageListResponse.class)))
                .thenReturn(new CanonCcapiStatusBackend.StorageListResponse(List.of(
                        new CanonCcapiStatusBackend.StorageEntry(
                                "sd", BASE_URL + "/ccapi/ver100/contents/sd", null,
                                "readwrite", 100L, 50L, 1L)
                )));

        StorageInfo storage = new CanonCcapiStatusBackend(BASE_URL, restTemplate).getStorageInfo();

        assertEquals(1, storage.volumes().size());
        assertEquals(50L, storage.totalFreeBytes());
        verify(restTemplate).getForObject(
                eq(BASE_URL + "/ccapi/ver100/topurlfordev"), eq(Map.class));
    }

    @Test
    void stopLiveViewSendsBothCanonRequestFields() throws Exception {
        when(restTemplate.getForObject(eq(BASE_URL + "/ccapi"), eq(Map.class)))
                .thenReturn(Map.of("ver110", List.of(descriptor(
                        "path", "/ccapi/ver110/shooting/liveview",
                        false, true, false))));
        when(restTemplate.exchange(
                eq(LIVE_VIEW_V110),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        CanonCcapiStatusBackend backend = new CanonCcapiStatusBackend(BASE_URL, restTemplate);

        backend.stopLiveView();

        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                eq(LIVE_VIEW_V110),
                eq(HttpMethod.POST),
                entity.capture(),
                eq(String.class));
        JsonNode body = new ObjectMapper().readTree((byte[]) entity.getValue().getBody());
        assertEquals("off", body.path("liveviewsize").asText());
        assertEquals("on", body.path("cameradisplay").asText());
    }

    private static Map<String, Object> apiRootWithVersionedStatus() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("ver100", List.of(
                descriptor("url", STORAGE_V100),
                descriptor("url", BATTERY_V100)
        ));
        root.put("ver110", List.of(
                descriptor("path", "/ccapi/ver110/devicestatus/currentstorage"),
                descriptor("path", "/ccapi/ver110/devicestatus/storage"),
                descriptor("path", "/ccapi/ver110/devicestatus/batterylist")
        ));
        return root;
    }

    private static Map<String, Object> descriptor(String field, String endpoint) {
        return descriptor(field, endpoint, true, false, false);
    }

    private static Map<String, Object> descriptor(
            String field,
            String endpoint,
            boolean get,
            boolean post,
            boolean delete) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(field, endpoint);
        result.put("get", get);
        result.put("post", post);
        result.put("put", false);
        result.put("delete", delete);
        return result;
    }
}
