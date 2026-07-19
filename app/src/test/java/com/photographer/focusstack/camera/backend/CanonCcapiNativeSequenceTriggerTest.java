package com.photographer.focusstack.camera.backend;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CanonCcapiNativeSequenceTriggerTest {

    @Test
    void sendsExactlyOnePostToHighestCompatibleAdvertisedShutterEndpoint() {
        String baseUrl = "http://camera.example:8080";
        String compatible = baseUrl + "/ccapi/ver100/shooting/control/shutterbutton";
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo(baseUrl + "/ccapi"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "ver100":[
                            {"url":"http://camera.example:8080/ccapi/ver100/shooting/control/shutterbutton",
                             "get":false,"post":true,"put":false,"delete":false}
                          ],
                          "ver140":[
                            {"path":"/ccapi/ver140/shooting/control/shutterbutton",
                             "get":true,"post":false,"put":false,"delete":false}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(compatible))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        CanonCcapiNativeFocusBracketingBackend backend =
                new CanonCcapiNativeFocusBracketingBackend(baseUrl, restTemplate);
        CanonCcapiNativeSequenceTrigger.trigger(backend);

        server.verify();
    }
}
