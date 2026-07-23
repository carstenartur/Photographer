package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.camera.exception.CameraOperationException;
import com.photographer.focusstack.common.camera.LiveViewFrame;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CanonCcapiScrollCaptureLifecycleTest {

    private static final String PROFILE = "scroll-capture";
    private static final String BASE = "http://camera.example";
    private static final String CONTROL = BASE + "/ccapi/ver100/shooting/liveview";
    private static final String SCROLL = CONTROL + "/scroll";

    @Test
    void failedScrollStopStillResetsControlStateBeforeNextFrame() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.getForObject(BASE + "/ccapi", Map.class))
                .thenReturn(scrollOnlyCatalog());
        when(restTemplate.exchange(
                eq(CONTROL),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));

        CanonCcapiScrollLiveViewReader reader = mock(CanonCcapiScrollLiveViewReader.class);
        LiveViewFrame frame = new LiveViewFrame(
                new byte[]{(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, (byte) 0xd9},
                640,
                480,
                Instant.EPOCH,
                "image/jpeg");
        when(reader.nextFrame(SCROLL)).thenReturn(frame);
        doThrow(new CameraOperationException(PROFILE, TIMEOUT, null))
                .when(reader).stop();

        CanonCcapiProductionBackend backend = new CanonCcapiProductionBackend(
                PROFILE, BASE, restTemplate, reader);

        assertThat(backend.getLiveView()).isSameAs(frame);
        assertThatThrownBy(backend::capture)
                .isInstanceOfSatisfying(CameraOperationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(TIMEOUT));
        assertThat(backend.getLiveView()).isSameAs(frame);

        verify(reader).stop();
        verify(restTemplate, times(2)).exchange(
                eq(CONTROL),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class));
    }

    private static Map<String, Object> scrollOnlyCatalog() {
        List<Map<String, Object>> descriptors = new ArrayList<>();
        descriptors.add(descriptor(
                "/ccapi/ver100/shooting/liveview", false, true, false));
        descriptors.add(descriptor(
                "/ccapi/ver100/shooting/liveview/flip", false, true, false));
        descriptors.add(descriptor(
                "/ccapi/ver100/shooting/liveview/scroll", true, false, true));
        return Map.of("ver100", List.copyOf(descriptors));
    }

    private static Map<String, Object> descriptor(
            String path,
            boolean get,
            boolean post,
            boolean delete) {
        return Map.of(
                "path", path,
                "get", get,
                "post", post,
                "put", false,
                "delete", delete);
    }
}
