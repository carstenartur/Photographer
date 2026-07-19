package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.camera.exception.CameraOperationException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.HttpServerErrorException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.ALREADY_STARTED;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.CANNOT_WRITE_TO_CARD;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.DEVICE_BUSY;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.DURING_SHOOTING_OR_RECORDING;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.ENDPOINT_NOT_FOUND;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.LIVE_VIEW_NOT_STARTED;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.METHOD_NOT_ALLOWED;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.MODE_NOT_SUPPORTED;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.NOT_STARTED;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.OUT_OF_FOCUS;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.SERVICE_UNAVAILABLE;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.TAKEN_IN_PREPARATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CcapiResponseErrorHandlerTest {

    private static final URI SAFE_URI = URI.create("http://camera.invalid/ccapi");

    @Test
    void classifiesPermanentContractFailures() {
        Fixture fixture = fixture();

        CameraOperationException missing = assertThrows(CameraOperationException.class,
                () -> fixture.handle(HttpStatus.NOT_FOUND, "{\"message\":\"url not found\"}"));
        CameraOperationException method = assertThrows(CameraOperationException.class,
                () -> fixture.handle(HttpStatus.METHOD_NOT_ALLOWED,
                        "{\"message\":\"method not allowed\"}"));

        assertEquals(ENDPOINT_NOT_FOUND, missing.reason());
        assertEquals(METHOD_NOT_ALLOWED, method.reason());
        assertFalse(missing.retryable());
        assertFalse(method.retryable());
    }

    @Test
    void classifiesEveryDocumentedNonRetryable503State() {
        Map<String, CameraOperationException.Reason> cases = Map.of(
                "Mode not supported", MODE_NOT_SUPPORTED,
                "Already started", ALREADY_STARTED,
                "Out of focus", OUT_OF_FOCUS,
                "Can not write to card", CANNOT_WRITE_TO_CARD,
                "Not started", NOT_STARTED,
                "unexpected future value", SERVICE_UNAVAILABLE);

        cases.forEach((message, expected) -> {
            Fixture fixture = fixture();
            CameraOperationException failure = assertThrows(CameraOperationException.class,
                    () -> fixture.handle(HttpStatus.SERVICE_UNAVAILABLE, json(message)));
            assertEquals(expected, failure.reason());
            assertFalse(failure.retryable());
            assertEquals(503, failure.downstreamStatus());
        });
    }

    @Test
    void retainsTypedReasonsBehindTheExistingBoundedRetryBridge() {
        Map<String, CameraOperationException.Reason> cases = Map.of(
                "Device busy", DEVICE_BUSY,
                "During shooting or recording", DURING_SHOOTING_OR_RECORDING,
                "Taken in preparation", TAKEN_IN_PREPARATION,
                "Live view not started", LIVE_VIEW_NOT_STARTED);

        cases.forEach((message, expected) -> {
            Fixture fixture = fixture();
            HttpServerErrorException bridged = assertThrows(HttpServerErrorException.class,
                    () -> fixture.handle(HttpStatus.SERVICE_UNAVAILABLE, json(message)));
            CameraOperationException typed = assertInstanceOf(
                    CameraOperationException.class, bridged.getCause());
            assertEquals(expected, typed.reason());
            assertEquals(expected.retryable(), typed.retryable());
            assertTrue(bridged.getResponseHeaders().isEmpty());
        });
    }

    @Test
    void neverCopiesCredentialLikeResponseDataIntoExceptionsOrMetrics() {
        Fixture fixture = fixture();
        String secret = "super-secret-password";
        String nonce = "private-camera-nonce";
        String body = "{\"message\":\"Device busy\",\"password\":\"" + secret
                + "\",\"nonce\":\"" + nonce + "\"}";

        HttpServerErrorException bridged = assertThrows(HttpServerErrorException.class,
                () -> fixture.handle(HttpStatus.SERVICE_UNAVAILABLE, body));
        CameraOperationException typed = assertInstanceOf(
                CameraOperationException.class, bridged.getCause());

        String combined = bridged.toString() + typed.toString()
                + bridged.getResponseBodyAsString()
                + fixture.registry().getMeters().toString();
        assertFalse(combined.contains(secret));
        assertFalse(combined.contains(nonce));
        assertEquals(1.0, fixture.registry().get(CcapiOutcomeRecorder.METER_NAME)
                .tag("outcome", "device_busy").counter().count());
    }

    @Test
    void oversizedOrMalformedBodiesBecomeSanitizedUnknownServiceState() {
        Fixture fixture = fixture();
        String oversized = "{\"message\":\"" + "x".repeat(5_000) + "\"}";

        CameraOperationException failure = assertThrows(CameraOperationException.class,
                () -> fixture.handle(HttpStatus.SERVICE_UNAVAILABLE, oversized));

        assertEquals(SERVICE_UNAVAILABLE, failure.reason());
        assertFalse(failure.getMessage().contains("x".repeat(100)));
    }

    private static Fixture fixture() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CcapiOutcomeRecorder recorder = new CcapiOutcomeRecorder(registry);
        return new Fixture(
                new CcapiResponseErrorHandler("camera-profile", true, recorder),
                registry);
    }

    private static String json(String message) {
        return "{\"message\":\"" + message + "\"}";
    }

    private record Fixture(
            CcapiResponseErrorHandler handler,
            SimpleMeterRegistry registry) {

        private void handle(HttpStatus status, String body) throws Exception {
            MockClientHttpResponse response = new MockClientHttpResponse(
                    body.getBytes(StandardCharsets.UTF_8), status);
            handler.handleError(response, status, SAFE_URI, HttpMethod.GET);
        }
    }
}
