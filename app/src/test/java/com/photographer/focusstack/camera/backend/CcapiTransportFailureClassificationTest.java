package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.app.config.CameraConfig;
import com.photographer.focusstack.app.config.CcapiAuthenticationProperties;
import com.photographer.focusstack.camera.exception.CameraOperationException;
import com.photographer.focusstack.common.model.CameraProfile;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;

import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.CANCELLED;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.CONNECTION_FAILED;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.TIMEOUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class CcapiTransportFailureClassificationTest {

    private CcapiTransport transport;

    @AfterEach
    void cleanup() {
        Thread.interrupted();
        if (transport != null) {
            transport.close();
        }
    }

    @Test
    void classifiesTimeoutWithoutLeakingTheCameraOrigin() {
        Fixture fixture = fixture();

        CameraOperationException failure = invokeFailure(
                fixture.interceptor(), new SocketTimeoutException("secret-host:8080 timed out"));

        assertEquals(TIMEOUT, failure.reason());
        assertFalse(failure.getMessage().contains("secret-host"));
        assertEquals(1.0, fixture.count("timeout"));
    }

    @Test
    void classifiesGenericInterruptedIoAsTimeoutWithoutInventingCancellation() {
        Fixture fixture = fixture();

        CameraOperationException failure = invokeFailure(
                fixture.interceptor(), new InterruptedIOException("read expired with private value"));

        assertEquals(TIMEOUT, failure.reason());
        assertFalse(failure.getMessage().contains("private value"));
        assertFalse(Thread.currentThread().isInterrupted());
        assertEquals(1.0, fixture.count("timeout"));
        assertEquals(0.0, fixture.count("cancelled"));
    }

    @Test
    void classifiesConnectionFailureWithoutLeakingTheCameraOrigin() {
        Fixture fixture = fixture();

        CameraOperationException failure = invokeFailure(
                fixture.interceptor(), new ConnectException("connection to secret-host refused"));

        assertEquals(CONNECTION_FAILED, failure.reason());
        assertFalse(failure.getMessage().contains("secret-host"));
        assertEquals(1.0, fixture.count("connection_failed"));
    }

    @Test
    void classifiesInterruptedThreadAsCancellationWithoutClearingInterruptFlag() {
        Fixture fixture = fixture();
        Thread.currentThread().interrupt();

        CameraOperationException failure = invokeFailure(
                fixture.interceptor(), new InterruptedIOException("cancelled with private value"));

        assertEquals(CANCELLED, failure.reason());
        assertFalse(failure.getMessage().contains("private value"));
        assertEquals(1.0, fixture.count("cancelled"));
        assertEquals(0.0, fixture.count("timeout"));
        assertTrue(Thread.currentThread().isInterrupted());
    }

    private Fixture fixture() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CcapiRestTemplateFactory factory = new CcapiRestTemplateFactory(
                new CameraConfig(
                        URI.create("http://127.0.0.1:8080"),
                        false,
                        Duration.ofSeconds(1)),
                new CcapiAuthenticationProperties(),
                new CcapiOutcomeRecorder(registry));
        transport = factory.create(profile(), URI.create("http://127.0.0.1:8080"));
        ClientHttpRequestInterceptor interceptor = transport.restTemplate()
                .getInterceptors().getFirst();
        return new Fixture(interceptor, registry);
    }

    private static CameraOperationException invokeFailure(
            ClientHttpRequestInterceptor interceptor,
            java.io.IOException failure) {
        HttpRequest request = mock(HttpRequest.class);
        ClientHttpRequestExecution execution = (ignoredRequest, ignoredBody) -> {
            throw failure;
        };
        return assertThrows(CameraOperationException.class,
                () -> interceptor.intercept(request, new byte[0], execution));
    }

    private static CameraProfile profile() {
        return new CameraProfile(
                "camera-profile",
                "Camera",
                "canon-ccapi",
                "http://127.0.0.1:8080",
                1,
                20,
                0.1);
    }

    private record Fixture(
            ClientHttpRequestInterceptor interceptor,
            SimpleMeterRegistry registry) {

        private double count(String outcome) {
            return registry.get(CcapiOutcomeRecorder.METER_NAME)
                    .tag("outcome", outcome)
                    .counter()
                    .count();
        }
    }
}
