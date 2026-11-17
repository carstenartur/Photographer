package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.common.camera.CameraMediaProvider;
import com.photographer.focusstack.common.camera.CameraMediaReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CanonCcapiPollingSemanticsTest {

    @Test
    void pollingIgnoresDeletedAndUpdatedContentsBeforeAddedContent() {
        String baseUrl = "http://camera.example:8080";
        String polling = baseUrl + "/ccapi/ver100/event/polling";
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo(polling))
                .andRespond(withSuccess("""
                        {
                          "deletedcontents":[
                            "/ccapi/ver100/contents/sd/100CANON/OLD.JPG"
                          ],
                          "updatedcontents":[
                            "/ccapi/ver100/contents/sd/100CANON/CHANGED.JPG"
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(polling))
                .andRespond(withSuccess("""
                        {"addedcontents":[
                          "/ccapi/ver100/contents/sd/100CANON/NEW.JPG"
                        ]}
                        """, MediaType.APPLICATION_JSON));

        CanonCcapiMediaClient client = new CanonCcapiMediaClient(
                "camera-profile",
                baseUrl,
                restTemplate,
                () -> CcapiEndpointCatalogTest.catalog(baseUrl, Map.of("ver100",
                        List.of(Map.of("url", polling, "get", true)))));

        Optional<CameraMediaReference> result = client.awaitAddedMedia(
                CameraMediaProvider.EventMode.POLLING,
                Duration.ofSeconds(2));

        assertEquals("NEW.JPG", result.orElseThrow().fileName());
        server.verify();
    }
}
