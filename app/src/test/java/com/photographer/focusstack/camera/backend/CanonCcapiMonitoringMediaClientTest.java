package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.common.camera.CameraMediaProvider;
import com.photographer.focusstack.common.camera.CameraMediaReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CanonCcapiMonitoringMediaClientTest {

    @Test
    void monitoringReturnsAddedMediaAndCancelsTheAdvertisedOperation() {
        String baseUrl = "http://camera.example:8080";
        String monitoring = baseUrl + "/ccapi/ver110/event/monitoring";
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo(monitoring))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"addedcontents":[
                          "/ccapi/ver110/contents/card1/100CANON/IMG_0042.JPG"
                        ]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(monitoring))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        CanonCcapiMediaClient client = new CanonCcapiMediaClient(
                "camera-profile",
                baseUrl,
                restTemplate,
                () -> Map.of("/ccapi/ver110/event/monitoring", monitoring),
                () -> Map.of("/ccapi/ver110/event/monitoring",
                        Set.of(HttpMethod.GET, HttpMethod.DELETE)));

        Optional<CameraMediaReference> result = client.awaitAddedMedia(
                CameraMediaProvider.EventMode.MONITORING,
                Duration.ofSeconds(1));

        assertEquals("IMG_0042.JPG", result.orElseThrow().fileName());
        server.verify();
    }
}
