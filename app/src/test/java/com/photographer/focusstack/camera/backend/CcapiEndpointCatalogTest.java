package com.photographer.focusstack.camera.backend;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CcapiEndpointCatalogTest {

    private static final String BASE_URL = "http://192.168.1.2:8080";
    private RestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
    }

    @Test
    void retainsEveryVersionAndNormalizesUrlAndPathDescriptors() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ver100", List.of(descriptor(
                "url", BASE_URL + "/ccapi/ver100/devicestatus/battery",
                true, false)));
        response.put("ver110", List.of(descriptor(
                "path", "/ccapi/ver110/devicestatus/storage",
                true, false)));
        response.put("ver140", List.of(descriptor(
                "path", "/ccapi/ver140/contents",
                true, false)));
        when(restTemplate.getForObject(eq(BASE_URL + "/ccapi"), eq(Map.class)))
                .thenReturn(response);

        CcapiEndpointCatalog catalog = CcapiEndpointCatalog.discover(BASE_URL, restTemplate);

        assertEquals(3, catalog.endpoints().size());
        CcapiEndpointCatalog.Endpoint storage = catalog
                .findExactPath("/devicestatus/storage", HttpMethod.GET)
                .orElseThrow();
        assertEquals("ver110", storage.version());
        assertEquals(110, storage.versionNumber());
        assertEquals("/ccapi/ver110/devicestatus/storage", storage.path());
        assertEquals(BASE_URL + "/ccapi/ver110/devicestatus/storage", storage.url());
        assertEquals("/ccapi/ver110/devicestatus/storage", storage.advertisedValue());
    }

    @Test
    void exactPathDoesNotMatchCurrentStorageSibling() {
        when(restTemplate.getForObject(eq(BASE_URL + "/ccapi"), eq(Map.class)))
                .thenReturn(Map.of("ver110", List.of(
                        descriptor("path", "/ccapi/ver110/devicestatus/currentstorage", true, false),
                        descriptor("path", "/ccapi/ver110/devicestatus/storage", true, false)
                )));

        CcapiEndpointCatalog.Endpoint endpoint = CcapiEndpointCatalog
                .discover(BASE_URL, restTemplate)
                .findExactPath("devicestatus/storage")
                .orElseThrow();

        assertEquals("/ccapi/ver110/devicestatus/storage", endpoint.path());
    }

    @Test
    void methodAwareLookupRejectsAdvertisedButUnsupportedMethod() {
        when(restTemplate.getForObject(eq(BASE_URL + "/ccapi"), eq(Map.class)))
                .thenReturn(Map.of("ver100", List.of(descriptor(
                        "url", BASE_URL + "/ccapi/ver100/functions/example",
                        true, false))));

        CcapiEndpointCatalog catalog = CcapiEndpointCatalog.discover(BASE_URL, restTemplate);

        assertTrue(catalog.findExactPath("/functions/example", HttpMethod.GET).isPresent());
        assertFalse(catalog.findExactPath("/functions/example", HttpMethod.POST).isPresent());
        assertTrue(catalog.findExactPath("/functions/example").orElseThrow().methodMetadataKnown());
    }

    @Test
    void plainFormatAEndpointListKeepsUnknownMethodMetadataUsable() {
        String versionRoot = "/ccapi/ver100";
        String storageUrl = BASE_URL + "/ccapi/ver100/devicestatus/storage";
        when(restTemplate.getForObject(eq(BASE_URL + "/ccapi"), eq(Map.class)))
                .thenReturn(Map.of("ver100", versionRoot));
        when(restTemplate.getForObject(eq(BASE_URL + versionRoot), eq(Object.class)))
                .thenReturn(List.of(storageUrl));

        CcapiEndpointCatalog.Endpoint endpoint = CcapiEndpointCatalog
                .discover(BASE_URL, restTemplate)
                .findExactPath("/devicestatus/storage", HttpMethod.GET)
                .orElseThrow();

        assertFalse(endpoint.methodMetadataKnown());
        assertTrue(endpoint.methods().isEmpty());
        assertEquals(storageUrl, endpoint.url());
    }

    @Test
    void choosesHighestVersionForSameEndpointSuffix() {
        when(restTemplate.getForObject(eq(BASE_URL + "/ccapi"), eq(Map.class)))
                .thenReturn(Map.of(
                        "ver100", List.of(descriptor(
                                "url", BASE_URL + "/ccapi/ver100/devicestatus/storage",
                                true, false)),
                        "ver110", List.of(descriptor(
                                "path", "/ccapi/ver110/devicestatus/storage",
                                true, false))
                ));

        CcapiEndpointCatalog.Endpoint endpoint = CcapiEndpointCatalog
                .discover(BASE_URL, restTemplate)
                .findExactPath("/devicestatus/storage")
                .orElseThrow();

        assertEquals("ver110", endpoint.version());
    }

    @Test
    void usesDocumentedDeveloperRootWhenOrdinaryListIsUnavailable() {
        when(restTemplate.getForObject(eq(BASE_URL + "/ccapi"), eq(Map.class)))
                .thenReturn(Map.of("value", "No List of APIs"));
        when(restTemplate.getForObject(
                eq(BASE_URL + "/ccapi/ver100/topurlfordev"), eq(Map.class)))
                .thenReturn(Map.of("ver100", List.of(descriptor(
                        "url", BASE_URL + "/ccapi/ver100/deviceinformation",
                        true, false))));

        CcapiEndpointCatalog catalog = CcapiEndpointCatalog.discover(BASE_URL, restTemplate);

        assertTrue(catalog.findExactPath("/deviceinformation", HttpMethod.GET).isPresent());
        verify(restTemplate).getForObject(
                eq(BASE_URL + "/ccapi/ver100/topurlfordev"), eq(Map.class));
    }

    @Test
    void unavailableDiscoveryProducesEmptyImmutableCatalog() {
        CcapiEndpointCatalog catalog = CcapiEndpointCatalog.discover(BASE_URL, restTemplate);

        assertTrue(catalog.endpoints().isEmpty());
        assertTrue(catalog.findExactPath("/deviceinformation").isEmpty());
    }

    static CcapiEndpointCatalog catalog(String baseUrl, Map<String, Object> versions) {
        RestTemplate discovery = mock(RestTemplate.class);
        when(discovery.getForObject(baseUrl + "/ccapi", Map.class)).thenReturn(versions);
        return CcapiEndpointCatalog.discover(baseUrl, discovery);
    }

    private static Map<String, Object> descriptor(String field, String endpoint,
                                                  boolean get, boolean post) {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put(field, endpoint);
        descriptor.put("get", get);
        descriptor.put("post", post);
        descriptor.put("put", false);
        descriptor.put("delete", false);
        return descriptor;
    }
}
