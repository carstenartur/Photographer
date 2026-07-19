package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.camera.exception.CameraOperationException;
import com.photographer.focusstack.common.camera.CameraMediaProvider;
import com.photographer.focusstack.common.camera.CameraMediaReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.CAMERA_REJECTED;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.CANCELLED;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.METHOD_NOT_ALLOWED;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CanonCcapiMediaClientTest {

    private static final String BASE_URL = "http://camera.example:8080";

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void listsVersion100AbsoluteUrlsAsRedactedMediaReferences() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        String contents = BASE_URL + "/ccapi/ver100/contents";
        String storage = BASE_URL + "/ccapi/ver100/contents/sd";
        String directory = storage + "/100CANON";

        server.expect(requestTo(contents))
                .andRespond(withSuccess("""
                        {"url":["http://camera.example:8080/ccapi/ver100/contents/sd"]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(storage))
                .andRespond(withSuccess("""
                        {"url":["http://camera.example:8080/ccapi/ver100/contents/sd/100CANON"]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(directory + "?type=all&kind=number"))
                .andRespond(withSuccess("""
                        {"contentsnumber":2,"pagenumber":1}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(directory + "?type=all&kind=list&page=1"))
                .andRespond(withSuccess("""
                        {"url":[
                          "http://camera.example:8080/ccapi/ver100/contents/sd/100CANON/IMG_0001.JPG",
                          "http://camera.example:8080/ccapi/ver100/contents/sd/100CANON/IMG_0002.CR3"
                        ]}
                        """, MediaType.APPLICATION_JSON));

        CameraMediaProvider.MediaPage page = client(
                restTemplate,
                Map.of(contents, contents),
                Map.of(contents, Set.of(HttpMethod.GET)))
                .listMedia(CameraMediaProvider.MediaQuery.bounded(10));

        assertEquals(2, page.items().size());
        assertEquals(2, page.totalAvailable());
        assertFalse(page.truncated());
        CameraMediaReference first = page.items().getFirst();
        assertEquals("sd", first.storage());
        assertEquals("ver100", first.apiVersion());
        assertEquals("/ccapi/ver100/contents/sd/100CANON/IMG_0001.JPG",
                first.relativePath());
        assertFalse(first.relativePath().contains("://"));
        server.verify();
    }

    @Test
    void listsVersion140FolderHierarchyAndAppliesGlobalItemBound() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        String contents = BASE_URL + "/ccapi/ver140/contents";
        String storage = BASE_URL + "/ccapi/ver140/contents/card1";
        String directoryPath = "/ccapi/ver140/contents/card1/DCIM/100CANON";
        String directory = BASE_URL + directoryPath;

        server.expect(requestTo(contents))
                .andRespond(withSuccess("""
                        {"path":["/ccapi/ver140/contents/card1"]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(storage))
                .andRespond(withSuccess("""
                        {"path":["/ccapi/ver140/contents/card1/DCIM/100CANON"]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(directory + "?type=all&kind=number"))
                .andRespond(withSuccess("""
                        {"contentsnumber":2,"pagenumber":1}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(directory + "?type=all&kind=list&page=1"))
                .andRespond(withSuccess("""
                        {"path":[
                          "/ccapi/ver140/contents/card1/DCIM/100CANON/IMG_0101.HIF",
                          "/ccapi/ver140/contents/card1/DCIM/100CANON/IMG_0102.CR3"
                        ]}
                        """, MediaType.APPLICATION_JSON));

        CameraMediaProvider.MediaPage page = client(
                restTemplate,
                Map.of(directoryPath.substring(0, directoryPath.indexOf("/card1")), contents),
                Map.of(directoryPath.substring(0, directoryPath.indexOf("/card1")),
                        Set.of(HttpMethod.GET)))
                .listMedia(CameraMediaProvider.MediaQuery.bounded(1));

        assertEquals(1, page.items().size());
        assertEquals(2, page.totalAvailable());
        assertTrue(page.truncated());
        assertEquals("card1", page.items().getFirst().storage());
        assertEquals("ver140", page.items().getFirst().apiVersion());
        assertTrue(page.items().getFirst().relativePath().contains("/DCIM/100CANON/"));
        server.verify();
    }

    @Test
    void rejectsCrossOriginValuesReturnedByCamera() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        String contents = BASE_URL + "/ccapi/ver100/contents";
        server.expect(requestTo(contents))
                .andRespond(withSuccess("""
                        {"url":["http://untrusted.example/ccapi/ver100/contents/sd"]}
                        """, MediaType.APPLICATION_JSON));

        CameraOperationException failure = assertThrows(CameraOperationException.class,
                () -> client(restTemplate,
                        Map.of(contents, contents),
                        Map.of(contents, Set.of(HttpMethod.GET)))
                        .listMedia(CameraMediaProvider.MediaQuery.bounded(10)));

        assertEquals(CAMERA_REJECTED, failure.reason());
        server.verify();
    }

    @Test
    void downloadsValidatedPartialRangeWithoutReadingBeyondBound() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        CameraMediaReference reference = reference("/ccapi/ver100/contents/sd/100CANON/IMG_0001.JPG");
        server.expect(requestTo(BASE_URL + reference.relativePath()))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Range", "bytes=4-7"))
                .andRespond(withStatus(HttpStatus.PARTIAL_CONTENT)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .header("Content-Range", "bytes 4-7/10")
                        .body(new byte[]{4, 5, 6, 7}));

        CameraMediaProvider.MediaChunk chunk = client(
                restTemplate, Map.of(), Map.of())
                .downloadMedia(reference, 4, 4);

        assertEquals(4, chunk.offset());
        assertEquals(10L, chunk.totalSize());
        assertFalse(chunk.complete());
        assertArrayEquals(new byte[]{4, 5, 6, 7}, chunk.data());
        server.verify();
    }

    @Test
    void rejectsServerThatIgnoresNonZeroRangeOffset() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        CameraMediaReference reference = reference("/ccapi/ver100/contents/sd/100CANON/IMG_0001.JPG");
        server.expect(requestTo(BASE_URL + reference.relativePath()))
                .andRespond(withSuccess(new byte[]{0, 1, 2, 3}, MediaType.APPLICATION_OCTET_STREAM));

        CameraOperationException failure = assertThrows(CameraOperationException.class,
                () -> client(restTemplate, Map.of(), Map.of())
                        .downloadMedia(reference, 4, 4));

        assertEquals(CAMERA_REJECTED, failure.reason());
        server.verify();
    }

    @Test
    void pollingSurfacesAddedContentAsVendorNeutralReference() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        String polling = BASE_URL + "/ccapi/ver100/event/polling";
        server.expect(requestTo(polling))
                .andRespond(withSuccess("""
                        {"addedcontents":[
                          "http://camera.example:8080/ccapi/ver100/contents/sd/100CANON/IMG_0003.JPG"
                        ]}
                        """, MediaType.APPLICATION_JSON));

        Optional<CameraMediaReference> result = client(
                restTemplate,
                Map.of(polling, polling),
                Map.of(polling, Set.of(HttpMethod.GET)))
                .awaitAddedMedia(CameraMediaProvider.EventMode.POLLING, Duration.ofSeconds(1));

        assertTrue(result.isPresent());
        assertEquals("IMG_0003.JPG", result.orElseThrow().fileName());
        assertFalse(result.orElseThrow().relativePath().contains("://"));
        server.verify();
    }

    @Test
    void pollingContinuationRequiresAndExecutesAdvertisedDeleteCancellation() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        String polling = BASE_URL + "/ccapi/ver110/event/polling";
        server.expect(requestTo(polling + "?continue=on"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"addedcontents":[
                          "/ccapi/ver110/contents/card1/100CANON/IMG_0004.CR3"
                        ]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(polling))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        Optional<CameraMediaReference> result = client(
                restTemplate,
                Map.of("/ccapi/ver110/event/polling", polling),
                Map.of("/ccapi/ver110/event/polling",
                        Set.of(HttpMethod.GET, HttpMethod.DELETE)))
                .awaitAddedMedia(
                        CameraMediaProvider.EventMode.POLLING_CONTINUATION,
                        Duration.ofSeconds(1));

        assertEquals("IMG_0004.CR3", result.orElseThrow().fileName());
        server.verify();
    }

    @Test
    void monitoringIsRejectedWhenDeleteCancellationWasNotAdvertised() {
        RestTemplate restTemplate = new RestTemplate();
        String monitoring = BASE_URL + "/ccapi/ver110/event/monitoring";

        CameraOperationException failure = assertThrows(CameraOperationException.class,
                () -> client(
                        restTemplate,
                        Map.of("/ccapi/ver110/event/monitoring", monitoring),
                        Map.of("/ccapi/ver110/event/monitoring", Set.of(HttpMethod.GET)))
                        .awaitAddedMedia(
                                CameraMediaProvider.EventMode.MONITORING,
                                Duration.ofSeconds(1)));

        assertEquals(METHOD_NOT_ALLOWED, failure.reason());
    }

    @Test
    void interruptedCallerGetsCancellationWithoutDisconnectionSemantics() {
        RestTemplate restTemplate = new RestTemplate();
        Thread.currentThread().interrupt();

        CameraOperationException failure = assertThrows(CameraOperationException.class,
                () -> client(restTemplate, Map.of(), Map.of())
                        .awaitAddedMedia(
                                CameraMediaProvider.EventMode.POLLING,
                                Duration.ofSeconds(1)));

        assertEquals(CANCELLED, failure.reason());
        assertTrue(Thread.currentThread().isInterrupted());
    }

    private static CanonCcapiMediaClient client(
            RestTemplate restTemplate,
            Map<String, String> endpoints,
            Map<String, Set<HttpMethod>> methods) {
        return new CanonCcapiMediaClient(
                "camera-profile", BASE_URL, restTemplate, () -> endpoints, () -> methods);
    }

    private static CameraMediaReference reference(String path) {
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        return new CameraMediaReference("sd", path, fileName, null, "ver100");
    }
}
