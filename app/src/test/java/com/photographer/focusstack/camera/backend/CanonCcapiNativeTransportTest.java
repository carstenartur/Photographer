package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.common.camera.CameraMediaProvider.EventMode;
import com.photographer.focusstack.common.camera.NativeFocusBracketingRequest;
import com.photographer.focusstack.common.camera.NativeFocusBracketingTransportException;
import com.photographer.focusstack.common.camera.NativeFocusBracketingTransportException.Reason;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CanonCcapiNativeTransportTest {

    private static final String BASE_URL = "http://camera.example:8080";
    private static final String SHUTTER = "/shooting/control/shutterbutton";
    private static final String CONTENTS = "/contents";
    private static final String POLLING = "/event/polling";
    private static final String MONITORING = "/event/monitoring";

    static Stream<Arguments> incompleteCatalogs() {
        String list = operation(CONTENTS, "get");
        String poll = operation(POLLING, "get");
        String shoot = operation(SHUTTER, "post");
        return Stream.of(
                Arguments.of(Reason.SHUTTER_UNAVAILABLE, List.of(list, poll)),
                Arguments.of(Reason.SHUTTER_UNAVAILABLE, List.of(operation(SHUTTER, "get"), list, poll)),
                Arguments.of(Reason.SHUTTER_UNAVAILABLE, List.of(operation(SHUTTER), list, poll)),
                Arguments.of(Reason.SHUTTER_UNAVAILABLE, List.of(operation("/nested" + SHUTTER, "post"), list, poll)),
                Arguments.of(Reason.MEDIA_LISTING_UNAVAILABLE, List.of(shoot, poll)),
                Arguments.of(Reason.MEDIA_LISTING_UNAVAILABLE, List.of(shoot, operation(CONTENTS, "post"), poll)),
                Arguments.of(Reason.EVENT_TRANSPORT_UNAVAILABLE, List.of(shoot, list)),
                Arguments.of(Reason.EVENT_TRANSPORT_UNAVAILABLE, List.of(shoot, list,
                        operation(MONITORING, "get"), operation(POLLING, "delete"))));
    }

    @ParameterizedTest
    @MethodSource("incompleteCatalogs")
    void rejectsIncompleteTransportBeforeSettingsOrShutter(Reason reason, List<String> operations) {
        RestTemplate template = new RestTemplate();
        MockRestServiceServer server = catalog(template, operations);
        var backend = new CanonCcapiProductionBackend("camera", BASE_URL, template);
        var request = new NativeFocusBracketingRequest(3, 2, false, false, false);

        assertEquals(reason, assertThrows(NativeFocusBracketingTransportException.class,
                backend::preflightNativeFocusBracketingTransport).reason());
        assertEquals(reason, assertThrows(NativeFocusBracketingTransportException.class,
                () -> backend.prepareNativeFocusBracketing(request)).reason());
        server.verify(); // Discovery GET is the only expected HTTP call.
    }

    static Stream<Arguments> observationModes() {
        return Stream.of(
                Arguments.of(EventMode.POLLING, List.of(operation(POLLING, "get"))),
                Arguments.of(EventMode.POLLING, List.of(operation(POLLING))),
                Arguments.of(EventMode.POLLING_CONTINUATION, List.of(operation(POLLING, "get", "delete"))),
                Arguments.of(EventMode.MONITORING, List.of(operation(POLLING, "get", "delete"),
                        operation(MONITORING, "get", "delete"))));
    }

    @ParameterizedTest
    @MethodSource("observationModes")
    void selectsUsableObservationModeWithoutChangingCamera(EventMode expected, List<String> events) {
        RestTemplate template = new RestTemplate();
        var operations = new java.util.ArrayList<>(List.of(operation(SHUTTER, "post"), operation(CONTENTS)));
        operations.addAll(events);
        MockRestServiceServer server = catalog(template, operations);
        var backend = new CanonCcapiProductionBackend("camera", BASE_URL, template);

        assertEquals(expected, backend.preflightNativeFocusBracketingTransport());
        server.verify();
    }

    private static MockRestServiceServer catalog(RestTemplate template, List<String> operations) {
        MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
        String json = "{\"ver140\":[" + String.join(",", operations) + "]}";
        server.expect(requestTo(BASE_URL + "/ccapi")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
        return server;
    }

    private static String operation(String path, String... methods) {
        StringBuilder descriptor = new StringBuilder("{\"path\":\"/ccapi/ver140").append(path).append('"');
        for (String method : methods) {
            descriptor.append(",\"").append(method).append("\":true");
        }
        return descriptor.append('}').toString();
    }
}
