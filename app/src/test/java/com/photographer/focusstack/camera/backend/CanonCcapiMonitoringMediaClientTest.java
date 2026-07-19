package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.camera.exception.CameraOperationException;
import com.photographer.focusstack.common.camera.CameraCapabilitySnapshot;
import com.photographer.focusstack.common.camera.CameraMediaProvider;
import com.photographer.focusstack.common.camera.CameraMediaReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.CAMERA_REJECTED;
import static com.photographer.focusstack.common.camera.CameraCapabilitySnapshot.Availability.AVAILABLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CanonCcapiMonitoringMediaClientTest {

    private static final String BASE_URL = "http://camera.example:8080";
    private static final String MONITORING = BASE_URL + "/ccapi/ver110/event/monitoring";

    @Test
    void monitoringParsesBinaryFramesAndCancelsTheAdvertisedOperation() throws IOException {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        byte[] stream = concatenate(
                frame("{}"),
                frame("""
                        {"addedcontents":[
                          "/ccapi/ver110/contents/card1/100CANON/IMG_0042.JPG"
                        ]}
                        """));
        server.expect(requestTo(MONITORING))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(stream, MediaType.APPLICATION_OCTET_STREAM));
        server.expect(requestTo(MONITORING))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        CanonCcapiMonitoringClient client = client(restTemplate);
        Optional<CameraMediaReference> result = client.awaitAddedMedia(Duration.ofSeconds(1));

        assertEquals("IMG_0042.JPG", result.orElseThrow().fileName());
        assertEquals("/ccapi/ver110/contents/card1/100CANON/IMG_0042.JPG",
                result.orElseThrow().relativePath());
        server.verify();
    }

    @Test
    void monitoringRejectsMalformedBinaryFrameAndStillCancels() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        byte[] malformed = new byte[]{(byte) 0xFF, 0x00, 0x01, 0, 0, 0, 0,
                (byte) 0xFF, (byte) 0xFF};
        server.expect(requestTo(MONITORING))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(malformed, MediaType.APPLICATION_OCTET_STREAM));
        server.expect(requestTo(MONITORING))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        CameraOperationException failure = assertThrows(CameraOperationException.class,
                () -> client(restTemplate).awaitAddedMedia(Duration.ofSeconds(1)));

        assertEquals(CAMERA_REJECTED, failure.reason());
        server.verify();
    }

    @Test
    void monitoringRejectsDeclaredFrameBeyondMemoryBoundBeforeAllocatingPayload() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        byte[] oversizedHeader = ByteBuffer.allocate(7)
                .put((byte) 0xFF)
                .put((byte) 0x00)
                .put((byte) 0x02)
                .putInt(4 * 1024 * 1024 + 1)
                .array();
        server.expect(requestTo(MONITORING))
                .andRespond(withSuccess(oversizedHeader, MediaType.APPLICATION_OCTET_STREAM));
        server.expect(requestTo(MONITORING))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        CameraOperationException failure = assertThrows(CameraOperationException.class,
                () -> client(restTemplate).awaitAddedMedia(Duration.ofSeconds(1)));

        assertEquals(CAMERA_REJECTED, failure.reason());
        server.verify();
    }

    @Test
    void productionCapabilityMarksAdvancedEventsAvailableOnlyFromGetDeleteCatalog() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo(BASE_URL + "/ccapi"))
                .andRespond(withSuccess("""
                        {"ver110":[
                          {"path":"/ccapi/ver110/event/polling",
                           "get":true,"post":false,"put":false,"delete":true},
                          {"path":"/ccapi/ver110/event/monitoring",
                           "get":true,"post":false,"put":false,"delete":true}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        CanonCcapiProductionBackend backend = new CanonCcapiProductionBackend(
                "camera-profile", BASE_URL, restTemplate);
        CameraCapabilitySnapshot snapshot = backend.getCapabilitySnapshot();

        assertCapability(snapshot, "event-polling-continuation");
        assertCapability(snapshot, "event-monitoring");
        server.verify();
    }

    private static CanonCcapiMonitoringClient client(RestTemplate restTemplate) {
        return new CanonCcapiMonitoringClient(
                "camera-profile",
                BASE_URL,
                restTemplate,
                () -> Map.of("/ccapi/ver110/event/monitoring", MONITORING),
                () -> Map.of("/ccapi/ver110/event/monitoring",
                        Set.of(HttpMethod.GET, HttpMethod.DELETE)));
    }

    private static byte[] frame(String json) {
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(2 + 1 + Integer.BYTES + data.length + 2)
                .put((byte) 0xFF)
                .put((byte) 0x00)
                .put((byte) 0x02)
                .putInt(data.length)
                .put(data)
                .put((byte) 0xFF)
                .put((byte) 0xFF)
                .array();
    }

    private static byte[] concatenate(byte[]... values) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] value : values) {
            output.write(value);
        }
        return output.toByteArray();
    }

    private static void assertCapability(CameraCapabilitySnapshot snapshot, String id) {
        CameraCapabilitySnapshot.Capability capability = snapshot.capabilities().stream()
                .filter(candidate -> candidate.id().equals(id))
                .findFirst()
                .orElseThrow();
        assertTrue(capability.advertised());
        assertTrue(capability.implemented());
        assertEquals(AVAILABLE, capability.availability());
        assertEquals(Set.of("DELETE", "GET"), capability.requiredMethods());
        assertEquals(Set.of("DELETE", "GET"), capability.advertisedMethods());
    }
}
