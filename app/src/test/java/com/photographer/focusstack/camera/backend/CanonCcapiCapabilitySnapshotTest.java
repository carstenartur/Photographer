package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.common.camera.CameraCapabilitySnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static com.photographer.focusstack.common.camera.CameraCapabilitySnapshot.Availability.ADVERTISED_NOT_IMPLEMENTED;
import static com.photographer.focusstack.common.camera.CameraCapabilitySnapshot.Availability.AVAILABLE;
import static com.photographer.focusstack.common.camera.CameraCapabilitySnapshot.Availability.METHOD_METADATA_UNKNOWN;
import static com.photographer.focusstack.common.camera.CameraCapabilitySnapshot.Availability.TEMPORARILY_UNAVAILABLE;
import static com.photographer.focusstack.common.camera.CameraCapabilitySnapshot.Availability.UNSUPPORTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CanonCcapiCapabilitySnapshotTest {

    private static final String BASE_URL = "http://camera.example:8080";

    @Test
    void powerShotG5xMarkIiDoesNotInventInterchangeableLensStatus() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo(BASE_URL + "/ccapi"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"ver100":[
                          {"url":"http://camera.example:8080/ccapi/ver100/deviceinformation","get":true,"post":false,"put":false,"delete":false},
                          {"url":"http://camera.example:8080/ccapi/ver100/devicestatus/battery","get":true,"post":false,"put":false,"delete":false},
                          {"url":"http://camera.example:8080/ccapi/ver100/shooting/control/shutterbutton","get":false,"post":true,"put":false,"delete":false},
                          {"url":"http://camera.example:8080/ccapi/ver100/event/polling","get":true,"post":false,"put":false,"delete":true}
                        ]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/ccapi/ver100/deviceinformation"))
                .andRespond(withSuccess("""
                        {"productname":"PowerShot G5 X Mark II","firmwareversion":"1.1.0"}
                        """, MediaType.APPLICATION_JSON));

        CameraCapabilitySnapshot snapshot = new CanonCcapiStatusBackend(BASE_URL, restTemplate)
                .getCapabilitySnapshot();

        assertEquals("PowerShot G5 X Mark II", snapshot.model());
        assertEquals("1.1.0", snapshot.firmwareVersion());
        assertTrue(snapshot.catalogAvailable());
        assertNull(snapshot.lens());
        assertEquals(UNSUPPORTED, capability(snapshot, "lens-status").availability());
        assertEquals(AVAILABLE, capability(snapshot, "still-capture").availability());
        assertEquals(AVAILABLE, capability(snapshot, "event-polling").availability());
        assertTrue(snapshot.advertisedEndpoints().stream()
                .allMatch(endpoint -> endpoint.path().startsWith("/")
                        && !endpoint.path().contains("://")));
        server.verify();
    }

    @Test
    void powerShotG7xMarkIiiKeepsMethodlessLegacyCatalogExplicitlyUnknown() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo(BASE_URL + "/ccapi"))
                .andRespond(withSuccess("""
                        {"ver100":"/ccapi/ver100"}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/ccapi/ver100"))
                .andRespond(withSuccess("""
                        [
                          "http://camera.example:8080/ccapi/ver100/deviceinformation",
                          "http://camera.example:8080/ccapi/ver100/devicestatus/storage",
                          "http://camera.example:8080/ccapi/ver100/shooting/liveview/flip"
                        ]
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/ccapi/ver100/deviceinformation"))
                .andRespond(withSuccess("""
                        {"productname":"PowerShot G7 X Mark III","firmwareversion":"1.3.2"}
                        """, MediaType.APPLICATION_JSON));

        CameraCapabilitySnapshot snapshot = new CanonCcapiStatusBackend(BASE_URL, restTemplate)
                .getCapabilitySnapshot();

        assertEquals("PowerShot G7 X Mark III", snapshot.model());
        assertEquals(METHOD_METADATA_UNKNOWN,
                capability(snapshot, "device-information").availability());
        assertEquals(METHOD_METADATA_UNKNOWN,
                capability(snapshot, "storage-status").availability());
        assertFalse(capability(snapshot, "live-view-frame").methodMetadataKnown());
        assertNull(snapshot.lens());
        server.verify();
    }

    @Test
    void representativeEosFixtureExposesAdvertisedLensAndNativeBracketingOnly() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo(BASE_URL + "/ccapi"))
                .andRespond(withSuccess("""
                        {"ver110":[
                          {"path":"/ccapi/ver110/deviceinformation","get":true,"post":false,"put":false,"delete":false},
                          {"path":"/ccapi/ver110/devicestatus/lens","get":true,"post":false,"put":false,"delete":false},
                          {"path":"/ccapi/ver110/shooting/settings/focusbracketing","get":true,"post":false,"put":true,"delete":false},
                          {"path":"/ccapi/ver110/shooting/settings/focusbracketing/numberofshots","get":true,"post":false,"put":true,"delete":false},
                          {"path":"/ccapi/ver110/shooting/settings/focusbracketing/focusincrement","get":true,"post":false,"put":true,"delete":false},
                          {"path":"/ccapi/ver110/shooting/settings/focusbracketing/exposuresmoothing","get":true,"post":false,"put":true,"delete":false},
                          {"path":"/ccapi/ver110/event/monitoring","get":true,"post":false,"put":false,"delete":true}
                        ]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/ccapi/ver110/deviceinformation"))
                .andRespond(withSuccess("""
                        {"productname":"EOS R5 Mark II","firmwareversion":"1.0.3"}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/ccapi/ver110/devicestatus/lens"))
                .andRespond(withSuccess("""
                        {"mount":true,"name":"RF100mm F2.8 L MACRO IS USM"}
                        """, MediaType.APPLICATION_JSON));

        CameraCapabilitySnapshot snapshot = new CanonCcapiNativeFocusBracketingBackend(
                BASE_URL, restTemplate).getCapabilitySnapshot();

        assertEquals("EOS R5 Mark II", snapshot.model());
        assertEquals("RF100mm F2.8 L MACRO IS USM", snapshot.lens().name());
        assertTrue(snapshot.lens().mounted());
        assertEquals(AVAILABLE, capability(snapshot, "lens-status").availability());
        assertEquals(AVAILABLE, capability(snapshot, "native-focus-bracketing").availability());
        assertEquals(AVAILABLE,
                capability(snapshot, "native-focus-bracketing-shot-count").availability());
        assertEquals(ADVERTISED_NOT_IMPLEMENTED,
                capability(snapshot, "event-monitoring").availability());
        server.verify();
    }

    @Test
    void advertisedLensCanReportDocumentedTemporaryStateWithoutLeakingBody() {
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.setErrorHandler(new CcapiResponseErrorHandler(
                "camera-profile", false,
                new CcapiOutcomeRecorder(new SimpleMeterRegistry())));
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo(BASE_URL + "/ccapi"))
                .andRespond(withSuccess("""
                        {"ver100":[
                          {"url":"http://camera.example:8080/ccapi/ver100/deviceinformation","get":true,"post":false,"put":false,"delete":false},
                          {"url":"http://camera.example:8080/ccapi/ver100/devicestatus/lens","get":true,"post":false,"put":false,"delete":false}
                        ]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/ccapi/ver100/deviceinformation"))
                .andRespond(withSuccess("""
                        {"productname":"EOS R7","firmwareversion":"1.6.0"}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/ccapi/ver100/devicestatus/lens"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"Device busy\",\"password\":\"must-not-leak\"}"));

        CameraCapabilitySnapshot snapshot = new CanonCcapiStatusBackend(BASE_URL, restTemplate)
                .getCapabilitySnapshot();
        CameraCapabilitySnapshot.Capability lens = capability(snapshot, "lens-status");

        assertNull(snapshot.lens());
        assertEquals(TEMPORARILY_UNAVAILABLE, lens.availability());
        assertEquals("DEVICE_BUSY", lens.condition());
        assertFalse(lens.condition().contains("must-not-leak"));
        server.verify();
    }

    private static CameraCapabilitySnapshot.Capability capability(
            CameraCapabilitySnapshot snapshot,
            String id) {
        return snapshot.capabilities().stream()
                .filter(capability -> capability.id().equals(id))
                .findFirst()
                .orElseThrow();
    }
}
