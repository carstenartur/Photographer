package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.camera.exception.CameraOperationException;
import com.photographer.focusstack.common.camera.LiveViewFrame;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.ALREADY_STARTED;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.DEVICE_BUSY;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.ENDPOINT_NOT_FOUND;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.LIVE_VIEW_NOT_STARTED;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.METHOD_NOT_ALLOWED;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.NOT_STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CcapiLiveViewClientTest {

    private static final String PROFILE = "camera-test";
    private static final String BASE = "http://camera.example";
    private static final String CONTROL_100 = BASE + "/ccapi/ver100/shooting/liveview";
    private static final String FLIP_100 = CONTROL_100 + "/flip";

    @Test
    void mapsAdvertisedCanonTransportsToVendorNeutralModes() {
        Fixture fixture = fixture(
                endpoints(
                        "/ccapi/ver100/shooting/liveview", CONTROL_100,
                        "/ccapi/ver100/shooting/liveview/flip", FLIP_100,
                        "/ccapi/ver110/shooting/liveview/scroll",
                        BASE + "/ccapi/ver110/shooting/liveview/scroll",
                        "/ccapi/ver120/shooting/liveview/multipart",
                        BASE + "/ccapi/ver120/shooting/liveview/multipart",
                        "/ccapi/ver140/shooting/liveview/rtp",
                        BASE + "/ccapi/ver140/shooting/liveview/rtp"),
                methods(
                        "/ccapi/ver100/shooting/liveview", Set.of(HttpMethod.POST),
                        "/ccapi/ver100/shooting/liveview/flip", Set.of(HttpMethod.GET),
                        "/ccapi/ver110/shooting/liveview/scroll",
                        Set.of(HttpMethod.GET, HttpMethod.DELETE),
                        "/ccapi/ver120/shooting/liveview/multipart", Set.of(HttpMethod.GET),
                        "/ccapi/ver140/shooting/liveview/rtp", Set.of(HttpMethod.POST)));

        assertThat(fixture.client.advertisedModes())
                .extracting(mode -> mode.mode().id())
                .containsExactly(
                        "single-frame",
                        "cursor-stream",
                        "multipart-stream",
                        "rtp-stream");
        assertThat(fixture.client.advertisedModes())
                .filteredOn(CcapiLiveViewClient.AdvertisedMode::frameRetrievalImplemented)
                .singleElement()
                .satisfies(mode -> {
                    assertThat(mode.mode()).isEqualTo(CcapiLiveViewClient.Mode.SINGLE_FRAME);
                    assertThat(mode.methodCompatible()).isTrue();
                });
    }

    @Test
    void rejectsAdvertisedFlipWhenGetIsExplicitlyUnavailableWithoutFallbackCall() {
        Fixture fixture = fixture(
                endpoints(
                        "/ccapi/ver100/shooting/liveview", CONTROL_100,
                        "/ccapi/ver100/shooting/liveview/flip", FLIP_100),
                methods(
                        "/ccapi/ver100/shooting/liveview", Set.of(HttpMethod.POST),
                        "/ccapi/ver100/shooting/liveview/flip", Set.of(HttpMethod.POST)));

        assertThat(fixture.client.supportsFrameRetrieval()).isFalse();
        assertThatThrownBy(fixture.client::getFrame)
                .isInstanceOfSatisfying(CameraOperationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(METHOD_NOT_ALLOWED));
        verify(fixture.restTemplate, never()).getForObject(FLIP_100, byte[].class);
        assertThat(fixture.poster.bodies).isEmpty();
    }

    @Test
    void acceptsExactMethodlessLegacyEndpointsAndUsesStableBodiesOnlyOnce() {
        Fixture fixture = fixture(
                endpoints(
                        "/ccapi/ver100/shooting/liveview", CONTROL_100,
                        "/ccapi/ver100/shooting/liveview/flip", FLIP_100),
                Map.of());
        byte[] jpeg = {(byte) 0xff, (byte) 0xd8, 1};
        when(fixture.restTemplate.getForObject(FLIP_100, byte[].class)).thenReturn(jpeg);

        LiveViewFrame first = fixture.client.getFrame();
        LiveViewFrame second = fixture.client.getFrame();
        fixture.client.stop();
        fixture.client.stop();

        assertThat(first.data()).containsExactly(jpeg);
        assertThat(second.data()).containsExactly(jpeg);
        assertThat(fixture.poster.bodyStrings()).containsExactly(
                "{\"liveviewsize\":\"medium\",\"cameradisplay\":\"on\"}",
                "{\"liveviewsize\":\"off\",\"cameradisplay\":\"on\"}");
        assertThat(fixture.client.state()).isEqualTo(CcapiLiveViewClient.State.STOPPED);
    }

    @Test
    void selectsHighestVersionThatActuallyAdvertisesGet() {
        String flip110 = BASE + "/ccapi/ver110/shooting/liveview/flip";
        Fixture fixture = fixture(
                endpoints(
                        "/ccapi/ver110/shooting/liveview", BASE + "/ccapi/ver110/shooting/liveview",
                        "/ccapi/ver100/shooting/liveview/flip", FLIP_100,
                        "/ccapi/ver110/shooting/liveview/flip", flip110),
                methods(
                        "/ccapi/ver110/shooting/liveview", Set.of(HttpMethod.POST),
                        "/ccapi/ver100/shooting/liveview/flip", Set.of(HttpMethod.GET),
                        "/ccapi/ver110/shooting/liveview/flip", Set.of(HttpMethod.POST)));
        when(fixture.restTemplate.getForObject(FLIP_100, byte[].class))
                .thenReturn(new byte[]{1});

        fixture.client.getFrame();

        verify(fixture.restTemplate).getForObject(FLIP_100, byte[].class);
        verify(fixture.restTemplate, never()).getForObject(flip110, byte[].class);
    }

    @Test
    void restartsExactlyOnceWhenCameraReportsLiveViewNotStarted() {
        Fixture fixture = standardFixture();
        when(fixture.restTemplate.getForObject(FLIP_100, byte[].class))
                .thenThrow(unavailable(LIVE_VIEW_NOT_STARTED, "Live view not started"))
                .thenReturn(new byte[]{7});

        LiveViewFrame frame = fixture.client.getFrame();

        assertThat(frame.data()).containsExactly(7);
        assertThat(fixture.poster.bodyStrings()).containsExactly(
                "{\"liveviewsize\":\"medium\",\"cameradisplay\":\"on\"}",
                "{\"liveviewsize\":\"medium\",\"cameradisplay\":\"on\"}");
        assertThat(fixture.client.state()).isEqualTo(CcapiLiveViewClient.State.STARTED);
    }

    @Test
    void doesNotMisclassifyDeviceBusyAsAStartCorrection() {
        Fixture fixture = standardFixture();
        when(fixture.restTemplate.getForObject(FLIP_100, byte[].class))
                .thenThrow(unavailable(DEVICE_BUSY, "Device busy"));

        assertThatThrownBy(fixture.client::getFrame)
                .isInstanceOfSatisfying(CameraOperationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(DEVICE_BUSY));
        assertThat(fixture.poster.bodyStrings()).containsExactly(
                "{\"liveviewsize\":\"medium\",\"cameradisplay\":\"on\"}");
    }

    @Test
    void treatsAlreadyStartedAndNotStartedAsIdempotentLifecycleResults() {
        Fixture fixture = standardFixture();
        fixture.poster.failNext(operation(ALREADY_STARTED));
        when(fixture.restTemplate.getForObject(FLIP_100, byte[].class))
                .thenReturn(new byte[]{3});

        assertThat(fixture.client.getFrame().data()).containsExactly(3);
        fixture.poster.failNext(operation(NOT_STARTED));
        fixture.client.stop();
        fixture.client.stop();

        assertThat(fixture.poster.bodyStrings()).containsExactly(
                "{\"liveviewsize\":\"medium\",\"cameradisplay\":\"on\"}",
                "{\"liveviewsize\":\"off\",\"cameradisplay\":\"on\"}");
        assertThat(fixture.client.state()).isEqualTo(CcapiLiveViewClient.State.STOPPED);
    }

    @Test
    void mapsScrollOnlyCatalogButDoesNotClaimFrameRetrieval() {
        Fixture fixture = fixture(
                endpoints(
                        "/ccapi/ver110/shooting/liveview", BASE + "/ccapi/ver110/shooting/liveview",
                        "/ccapi/ver110/shooting/liveview/scroll",
                        BASE + "/ccapi/ver110/shooting/liveview/scroll"),
                methods(
                        "/ccapi/ver110/shooting/liveview", Set.of(HttpMethod.POST),
                        "/ccapi/ver110/shooting/liveview/scroll",
                        Set.of(HttpMethod.GET, HttpMethod.DELETE)));

        assertThat(fixture.client.advertisedModes())
                .singleElement()
                .satisfies(mode -> {
                    assertThat(mode.mode()).isEqualTo(CcapiLiveViewClient.Mode.CURSOR_STREAM);
                    assertThat(mode.methodCompatible()).isTrue();
                    assertThat(mode.frameRetrievalImplemented()).isFalse();
                });
        assertThat(fixture.client.supportsFrameRetrieval()).isFalse();
        assertThatThrownBy(fixture.client::getFrame)
                .isInstanceOfSatisfying(CameraOperationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(ENDPOINT_NOT_FOUND));
    }

    private static Fixture standardFixture() {
        return fixture(
                endpoints(
                        "/ccapi/ver100/shooting/liveview", CONTROL_100,
                        "/ccapi/ver100/shooting/liveview/flip", FLIP_100),
                methods(
                        "/ccapi/ver100/shooting/liveview", Set.of(HttpMethod.POST),
                        "/ccapi/ver100/shooting/liveview/flip", Set.of(HttpMethod.GET)));
    }

    private static Fixture fixture(
            Map<String, String> endpoints,
            Map<String, Set<HttpMethod>> methods) {
        RestTemplate restTemplate = mock(RestTemplate.class);
        RecordingPoster poster = new RecordingPoster();
        CcapiLiveViewClient client = new CcapiLiveViewClient(
                PROFILE,
                restTemplate,
                () -> endpoints,
                () -> methods,
                poster,
                Duration.ZERO,
                ignored -> { });
        return new Fixture(restTemplate, poster, client);
    }

    private static Map<String, String> endpoints(String... values) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(values[index], values[index + 1]);
        }
        return Map.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Set<HttpMethod>> methods(Object... values) {
        Map<String, Set<HttpMethod>> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], (Set<HttpMethod>) values[index + 1]);
        }
        return Map.copyOf(result);
    }

    private static CameraOperationException operation(
            CameraOperationException.Reason reason) {
        return new CameraOperationException(PROFILE, reason, 503);
    }

    private static HttpServerErrorException.ServiceUnavailable unavailable(
            CameraOperationException.Reason reason,
            String message) {
        HttpServerErrorException failure = HttpServerErrorException.create(
                HttpStatus.SERVICE_UNAVAILABLE,
                HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase(),
                HttpHeaders.EMPTY,
                message.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        failure.initCause(operation(reason));
        return (HttpServerErrorException.ServiceUnavailable) failure;
    }

    private record Fixture(
            RestTemplate restTemplate,
            RecordingPoster poster,
            CcapiLiveViewClient client) { }

    private static final class RecordingPoster implements CcapiLiveViewClient.Poster {
        private final List<String> urls = new ArrayList<>();
        private final List<byte[]> bodies = new ArrayList<>();
        private final ArrayDeque<RuntimeException> failures = new ArrayDeque<>();

        @Override
        public void post(String url, Object body) {
            urls.add(url);
            bodies.add(((byte[]) body).clone());
            RuntimeException failure = failures.pollFirst();
            if (failure != null) {
                throw failure;
            }
        }

        void failNext(RuntimeException failure) {
            failures.addLast(failure);
        }

        List<String> bodyStrings() {
            return bodies.stream()
                    .map(body -> new String(body, StandardCharsets.UTF_8))
                    .toList();
        }
    }
}
