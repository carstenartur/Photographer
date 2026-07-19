package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.common.camera.IntegerSettingRange;
import com.photographer.focusstack.common.camera.NativeFocusBracketingCapabilities;
import com.photographer.focusstack.common.camera.NativeFocusBracketingRequest;
import com.photographer.focusstack.common.camera.NativeFocusBracketingSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CanonCcapiNativeFocusBracketingBackendTest {

    private static final String BASE_URL = "http://192.168.1.2:8080";
    private static final String ENABLED_SUFFIX = "/shooting/settings/focusbracketing";
    private static final String SHOTS_SUFFIX = ENABLED_SUFFIX + "/numberofshots";
    private static final String INCREMENT_SUFFIX = ENABLED_SUFFIX + "/focusincrement";
    private static final String SMOOTHING_SUFFIX = ENABLED_SUFFIX + "/exposuresmoothing";
    private static final String DEPTH_SUFFIX = ENABLED_SUFFIX + "/depthcomposite";
    private static final String CROP_SUFFIX = ENABLED_SUFFIX + "/cropdepthcomp";

    private static final String ENABLED_URL = url(140, ENABLED_SUFFIX);
    private static final String SHOTS_URL = url(140, SHOTS_SUFFIX);
    private static final String INCREMENT_URL = url(140, INCREMENT_SUFFIX);
    private static final String SMOOTHING_URL = url(140, SMOOTHING_SUFFIX);

    private RestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
    }

    @Test
    void derivesExecutableCapabilitiesOnlyFromExactGetAndPutEndpoints() {
        advertise(140,
                descriptor(140, ENABLED_SUFFIX, true, true),
                descriptor(140, SHOTS_SUFFIX, true, true),
                descriptor(140, INCREMENT_SUFFIX, true, true),
                descriptor(140, SMOOTHING_SUFFIX, true, true),
                descriptor(140, DEPTH_SUFFIX, true, false),
                descriptor(140, CROP_SUFFIX, false, true));
        stubBoolean(ENABLED_URL, "disable", "enable", "disable");
        stubInteger(SHOTS_URL, 100, 2, 999, 1);
        stubInteger(INCREMENT_URL, 3, 1, 10, 1);
        stubBoolean(SMOOTHING_URL, "disable", "enable", "disable");

        NativeFocusBracketingCapabilities capabilities = backend()
                .getNativeFocusBracketingCapabilities();

        assertTrue(capabilities.executable());
        assertEquals(new IntegerSettingRange(2, 999, 1),
                capabilities.numberOfShots().orElseThrow());
        assertEquals(new IntegerSettingRange(1, 10, 1),
                capabilities.focusIncrement().orElseThrow());
        assertTrue(capabilities.exposureSmoothingSupported());
        assertFalse(capabilities.depthCompositionSupported());
        assertFalse(capabilities.cropDepthCompositionSupported());
        verify(restTemplate, never()).exchange(
                anyString(), eq(HttpMethod.PUT), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void prefixedLookalikePathsCannotAuthorizeNativeSettingWrites() {
        advertise(140,
                descriptorPath("/ccapi/ver140/vendor" + ENABLED_SUFFIX, true, true),
                descriptorPath("/ccapi/ver140/vendor" + SHOTS_SUFFIX, true, true),
                descriptorPath("/ccapi/ver140/vendor" + INCREMENT_SUFFIX, true, true));

        CanonCcapiNativeFocusBracketingBackend backend = backend();
        NativeFocusBracketingCapabilities capabilities =
                backend.getNativeFocusBracketingCapabilities();

        assertFalse(capabilities.executable());
        verify(restTemplate, never()).getForObject(
                anyString(), eq(CanonCcapiNativeFocusBracketingBackend.BooleanSettingResponse.class));
        verify(restTemplate, never()).getForObject(
                anyString(), eq(CanonCcapiNativeFocusBracketingBackend.IntegerSettingResponse.class));
        verify(restTemplate, never()).exchange(
                anyString(), eq(HttpMethod.PUT), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void preparesAndRestoresSettingsInDeterministicWireOrder() {
        advertiseRequiredSettings(140);
        stubRequiredSettingResponses();
        when(restTemplate.exchange(
                anyString(), eq(HttpMethod.PUT), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));

        CanonCcapiNativeFocusBracketingBackend backend = backend();
        NativeFocusBracketingSession session = backend.prepareNativeFocusBracketing(
                new NativeFocusBracketingRequest(20, 5, false, false, false));
        session.close();

        InOrder order = inOrder(restTemplate);
        order.verify(restTemplate).exchange(
                eq(SHOTS_URL), eq(HttpMethod.PUT), entityContaining("\"value\":20"), eq(String.class));
        order.verify(restTemplate).exchange(
                eq(INCREMENT_URL), eq(HttpMethod.PUT), entityContaining("\"value\":5"), eq(String.class));
        order.verify(restTemplate).exchange(
                eq(ENABLED_URL), eq(HttpMethod.PUT), entityContaining("\"value\":\"enable\""), eq(String.class));
        order.verify(restTemplate).exchange(
                eq(ENABLED_URL), eq(HttpMethod.PUT), entityContaining("\"value\":\"disable\""), eq(String.class));
        order.verify(restTemplate).exchange(
                eq(INCREMENT_URL), eq(HttpMethod.PUT), entityContaining("\"value\":2"), eq(String.class));
        order.verify(restTemplate).exchange(
                eq(SHOTS_URL), eq(HttpMethod.PUT), entityContaining("\"value\":10"), eq(String.class));
        order.verify(restTemplate).exchange(
                eq(ENABLED_URL), eq(HttpMethod.PUT), entityContaining("\"value\":\"disable\""), eq(String.class));
        verify(restTemplate, times(7)).exchange(
                anyString(), eq(HttpMethod.PUT), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void usesLowerVersionOnlyWhenThatExactEndpointAdvertisesBothMethods() {
        when(restTemplate.getForObject(eq(BASE_URL + "/ccapi"), eq(Map.class)))
                .thenReturn(Map.of(
                        "ver100", List.of(
                                descriptor(100, ENABLED_SUFFIX, true, true),
                                descriptor(100, SHOTS_SUFFIX, true, true),
                                descriptor(100, INCREMENT_SUFFIX, true, true)),
                        "ver140", List.of(
                                descriptor(140, ENABLED_SUFFIX, true, false),
                                descriptor(140, SHOTS_SUFFIX, true, false),
                                descriptor(140, INCREMENT_SUFFIX, true, false))));
        String enabledV100 = url(100, ENABLED_SUFFIX);
        String shotsV100 = url(100, SHOTS_SUFFIX);
        String incrementV100 = url(100, INCREMENT_SUFFIX);
        stubBoolean(enabledV100, "disable", "enable", "disable");
        stubInteger(shotsV100, 10, 2, 100, 1);
        stubInteger(incrementV100, 2, 1, 10, 1);

        NativeFocusBracketingCapabilities capabilities = backend()
                .getNativeFocusBracketingCapabilities();

        assertTrue(capabilities.executable());
        verify(restTemplate).getForObject(
                eq(enabledV100), eq(CanonCcapiNativeFocusBracketingBackend.BooleanSettingResponse.class));
        verify(restTemplate, never()).getForObject(
                eq(ENABLED_URL), eq(CanonCcapiNativeFocusBracketingBackend.BooleanSettingResponse.class));
    }

    @Test
    void methodlessLegacyListCannotAuthorizeNativeSettingWrites() {
        when(restTemplate.getForObject(eq(BASE_URL + "/ccapi"), eq(Map.class)))
                .thenReturn(Map.of("ver100", List.of(
                        url(100, ENABLED_SUFFIX),
                        url(100, SHOTS_SUFFIX),
                        url(100, INCREMENT_SUFFIX))));

        CanonCcapiNativeFocusBracketingBackend backend = backend();
        NativeFocusBracketingCapabilities capabilities =
                backend.getNativeFocusBracketingCapabilities();

        assertFalse(capabilities.executable());
        assertThrows(IllegalArgumentException.class,
                () -> backend.prepareNativeFocusBracketing(
                        new NativeFocusBracketingRequest(10, 2, false, false, false)));
        verify(restTemplate, never()).exchange(
                anyString(), eq(HttpMethod.PUT), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void retriesIdempotentSettingPutWhenCanonReportsDeviceBusy() {
        advertiseRequiredSettings(140);
        stubRequiredSettingResponses();
        HttpServerErrorException busy = HttpServerErrorException.create(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Service Unavailable",
                HttpHeaders.EMPTY,
                "{\"message\":\"Device busy\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        when(restTemplate.exchange(
                eq(SHOTS_URL),
                eq(HttpMethod.PUT),
                any(HttpEntity.class),
                eq(String.class)))
                .thenThrow(busy)
                .thenReturn(ResponseEntity.ok("{}"));
        when(restTemplate.exchange(
                eq(INCREMENT_URL),
                eq(HttpMethod.PUT),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        when(restTemplate.exchange(
                eq(ENABLED_URL),
                eq(HttpMethod.PUT),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));

        try (NativeFocusBracketingSession ignored =
                     new CanonCcapiNativeFocusBracketingBackend(BASE_URL, restTemplate, 1, 0L)
                             .prepareNativeFocusBracketing(
                                     new NativeFocusBracketingRequest(
                                             20, 5, false, false, false))) {
            // Settings remain prepared until the session closes.
        }

        verify(restTemplate, times(2)).exchange(
                eq(SHOTS_URL),
                eq(HttpMethod.PUT),
                entityContaining("\"value\":20"),
                eq(String.class));
    }

    private CanonCcapiNativeFocusBracketingBackend backend() {
        return new CanonCcapiNativeFocusBracketingBackend(BASE_URL, restTemplate, 1, 0L);
    }

    private void advertiseRequiredSettings(int version) {
        advertise(version,
                descriptor(version, ENABLED_SUFFIX, true, true),
                descriptor(version, SHOTS_SUFFIX, true, true),
                descriptor(version, INCREMENT_SUFFIX, true, true));
    }

    @SafeVarargs
    private final void advertise(int version, Map<String, Object>... descriptors) {
        when(restTemplate.getForObject(eq(BASE_URL + "/ccapi"), eq(Map.class)))
                .thenReturn(Map.of("ver" + version, List.of(descriptors)));
    }

    private void stubRequiredSettingResponses() {
        stubBoolean(ENABLED_URL, "disable", "enable", "disable");
        stubInteger(SHOTS_URL, 10, 2, 999, 1);
        stubInteger(INCREMENT_URL, 2, 1, 10, 1);
    }

    private void stubBoolean(String url, String value, String... ability) {
        when(restTemplate.getForObject(
                eq(url),
                eq(CanonCcapiNativeFocusBracketingBackend.BooleanSettingResponse.class)))
                .thenReturn(new CanonCcapiNativeFocusBracketingBackend.BooleanSettingResponse(
                        value, List.of(ability)));
    }

    private void stubInteger(
            String url,
            int value,
            int min,
            int max,
            int step) {
        when(restTemplate.getForObject(
                eq(url),
                eq(CanonCcapiNativeFocusBracketingBackend.IntegerSettingResponse.class)))
                .thenReturn(new CanonCcapiNativeFocusBracketingBackend.IntegerSettingResponse(
                        value,
                        new CanonCcapiNativeFocusBracketingBackend.RangeAbility(
                                min, max, step)));
    }

    private static Map<String, Object> descriptor(
            int version,
            String suffix,
            boolean get,
            boolean put) {
        return descriptorPath("/ccapi/ver" + version + suffix, get, put);
    }

    private static Map<String, Object> descriptorPath(
            String path,
            boolean get,
            boolean put) {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("path", path);
        descriptor.put("get", get);
        descriptor.put("post", false);
        descriptor.put("put", put);
        descriptor.put("delete", false);
        return descriptor;
    }

    private static HttpEntity<byte[]> entityContaining(String expected) {
        return argThat(entity -> entity != null
                && entity.getBody() != null
                && new String(entity.getBody(), StandardCharsets.UTF_8).contains(expected));
    }

    private static String url(int version, String suffix) {
        return BASE_URL + "/ccapi/ver" + version + suffix;
    }
}
