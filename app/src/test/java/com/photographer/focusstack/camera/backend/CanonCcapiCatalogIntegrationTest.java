package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.common.camera.CameraInfo;
import com.photographer.focusstack.common.camera.Operation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CanonCcapiCatalogIntegrationTest {

    private static final String BASE_URL = "http://192.168.1.2:8080";
    private static final String DEVICE_V100 = BASE_URL + "/ccapi/ver100/deviceinformation";
    private static final String DEVICE_V110 = BASE_URL + "/ccapi/ver110/deviceinformation";
    private static final String LIVE_VIEW_V110 = BASE_URL + "/ccapi/ver110/shooting/liveview";

    private RestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
    }

    @Test
    void inheritedOperationsUseHighestAdvertisedExactEndpoint() {
        when(restTemplate.getForObject(eq(BASE_URL + "/ccapi"), eq(Map.class)))
                .thenReturn(Map.of(
                        "ver100", List.of(descriptor("url", DEVICE_V100, true, false)),
                        "ver110", List.of(descriptor(
                                "path", "/ccapi/ver110/deviceinformation", true, false))
                ));
        when(restTemplate.getForObject(
                eq(DEVICE_V110), eq(CanonCcapiBackend.DeviceInformation.class)))
                .thenReturn(new CanonCcapiBackend.DeviceInformation(
                        "Canon Test Camera", "SERIAL-110", "1.1.0", null));

        CanonCcapiStatusBackend backend = new CanonCcapiStatusBackend(BASE_URL, restTemplate);
        CameraInfo info = backend.getInfo();

        assertEquals("Canon Test Camera", info.model());
        assertEquals("ccapi-SERIAL-110", info.id());
        verify(restTemplate).getForObject(
                eq(DEVICE_V110), eq(CanonCcapiBackend.DeviceInformation.class));
        verify(restTemplate, never()).getForObject(
                eq(DEVICE_V100), eq(CanonCcapiBackend.DeviceInformation.class));
        verify(restTemplate, times(1)).getForObject(eq(BASE_URL + "/ccapi"), eq(Map.class));
    }

    @Test
    void retainsAllVersionsButMakesExactCapabilityDecision() {
        String shutterV100 = BASE_URL + "/ccapi/ver100/shooting/control/shutterbutton";
        String shutterV110 = BASE_URL + "/ccapi/ver110/shooting/control/shutterbutton";
        when(restTemplate.getForObject(eq(BASE_URL + "/ccapi"), eq(Map.class)))
                .thenReturn(Map.of(
                        "ver100", List.of(descriptor("url", shutterV100, false, true)),
                        "ver110", List.of(descriptor(
                                "path", "/ccapi/ver110/shooting/control/shutterbutton", false, true))
                ));

        CanonCcapiStatusBackend backend = new CanonCcapiStatusBackend(BASE_URL, restTemplate);

        assertEquals(shutterV110, backend.findEndpoint("shutterbutton"));
        assertTrue(backend.supports(Operation.CAPTURE));
        assertEquals(2, backend.getDiscoveredEndpoints().size());
        assertEquals(Set.of(HttpMethod.POST),
                backend.getDiscoveredEndpointMethods()
                        .get("/ccapi/ver110/shooting/control/shutterbutton"));
    }

    @Test
    void siblingEndpointCannotSatisfyExactStatusLookup() {
        when(restTemplate.getForObject(eq(BASE_URL + "/ccapi"), eq(Map.class)))
                .thenReturn(Map.of("ver110", List.of(descriptor(
                        "path", "/ccapi/ver110/devicestatus/currentstorage", true, false))));

        CanonCcapiStatusBackend backend = new CanonCcapiStatusBackend(BASE_URL, restTemplate);

        assertNull(backend.findEndpoint("/devicestatus/storage"));
        assertEquals(BASE_URL + "/ccapi/ver110/devicestatus/currentstorage",
                backend.findEndpoint("/devicestatus/currentstorage"));
    }

    @Test
    void methodMetadataIsAppliedWithoutSubstringHeuristics() {
        when(restTemplate.getForObject(eq(BASE_URL + "/ccapi"), eq(Map.class)))
                .thenReturn(Map.of("ver110", List.of(descriptor(
                        "path", "/ccapi/ver110/shooting/liveview", true, false))));

        CanonCcapiStatusBackend backend = new CanonCcapiStatusBackend(BASE_URL, restTemplate);

        assertTrue(backend.endpointAllows("/shooting/liveview", HttpMethod.GET, false));
        assertFalse(backend.endpointAllows("/shooting/liveview", HttpMethod.POST, true));
        assertTrue(backend.endpointAllows("/shooting/unknown", HttpMethod.POST, true));
    }

    @Test
    void stopLiveViewPostsExactlyOnceToAdvertisedBaseEndpoint() {
        when(restTemplate.getForObject(eq(BASE_URL + "/ccapi"), eq(Map.class)))
                .thenReturn(Map.of("ver110", List.of(
                        descriptor("path", "/ccapi/ver110/shooting/liveview", false, true),
                        descriptor("path", "/ccapi/ver110/shooting/liveview/flip", true, false)
                )));
        when(restTemplate.exchange(
                eq(LIVE_VIEW_V110),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));

        CanonCcapiStatusBackend backend = new CanonCcapiStatusBackend(BASE_URL, restTemplate);
        backend.stopLiveView();

        verify(restTemplate, times(1)).exchange(
                eq(LIVE_VIEW_V110),
                eq(HttpMethod.POST),
                entityContaining("\"liveviewsize\":\"off\""),
                eq(String.class));
        verify(restTemplate, never()).exchange(
                eq(BASE_URL + "/ccapi/ver110/shooting/liveview/flip"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class));
    }

    @Test
    void stopLiveViewDoesNotCallEndpointThatRejectsPost() {
        when(restTemplate.getForObject(eq(BASE_URL + "/ccapi"), eq(Map.class)))
                .thenReturn(Map.of("ver110", List.of(descriptor(
                        "path", "/ccapi/ver110/shooting/liveview", true, false))));

        new CanonCcapiStatusBackend(BASE_URL, restTemplate).stopLiveView();

        verify(restTemplate, never()).exchange(
                anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
    }

    private static HttpEntity<byte[]> entityContaining(String expected) {
        return argThat(entity -> {
            if (entity == null || entity.getBody() == null) {
                return false;
            }
            return new String(entity.getBody(), StandardCharsets.UTF_8).contains(expected);
        });
    }

    private static Map<String, Object> descriptor(
            String locationField, String endpoint, boolean get, boolean post) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(locationField, endpoint);
        result.put("get", get);
        result.put("post", post);
        result.put("put", false);
        result.put("delete", false);
        return result;
    }
}
