package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.camera.exception.CameraOperationException;
import com.photographer.focusstack.common.camera.CameraCapabilitySnapshot;
import com.photographer.focusstack.common.camera.LiveViewFrame;
import com.photographer.focusstack.common.camera.Operation;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.METHOD_NOT_ALLOWED;
import static com.photographer.focusstack.common.camera.CameraCapabilitySnapshot.Availability.AVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CanonCcapiProductionLiveViewTest {

    private static final String PROFILE = "camera-production";
    private static final String BASE = "http://camera.example";
    private static final String CONTROL = BASE + "/ccapi/ver100/shooting/liveview";
    private static final String FLIP = CONTROL + "/flip";

    @Test
    void productionBackendUsesCapabilityDrivenLifecycleAndStableBodies() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.getForObject(BASE + "/ccapi", Map.class))
                .thenReturn(catalog(true));
        when(restTemplate.exchange(
                eq(CONTROL),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        when(restTemplate.getForObject(FLIP, byte[].class))
                .thenReturn(new byte[]{(byte) 0xff, (byte) 0xd8, 5});
        CanonCcapiProductionBackend backend = new CanonCcapiProductionBackend(
                PROFILE, BASE, restTemplate);

        assertThat(backend.supports(Operation.LIVE_VIEW)).isTrue();
        LiveViewFrame frame = backend.getLiveView();
        backend.stopLiveView();
        backend.stopLiveView();

        assertThat(frame.data()).containsExactly((byte) 0xff, (byte) 0xd8, (byte) 5);
        ArgumentCaptor<HttpEntity> entities = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate, times(2)).exchange(
                eq(CONTROL),
                eq(HttpMethod.POST),
                entities.capture(),
                eq(String.class));
        assertThat(entities.getAllValues())
                .extracting(entity -> new String(
                        (byte[]) entity.getBody(), StandardCharsets.UTF_8))
                .containsExactly(
                        "{\"liveviewsize\":\"medium\",\"cameradisplay\":\"on\"}",
                        "{\"liveviewsize\":\"off\",\"cameradisplay\":\"on\"}");
    }

    @Test
    void productionSnapshotExposesImplementedScrollMode() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.getForObject(BASE + "/ccapi", Map.class))
                .thenReturn(catalog(true));
        CanonCcapiProductionBackend backend = new CanonCcapiProductionBackend(
                PROFILE, BASE, restTemplate);

        CameraCapabilitySnapshot.Capability scroll = backend.getCapabilitySnapshot()
                .capabilities().stream()
                .filter(capability -> "live-view-scroll".equals(capability.id()))
                .findFirst()
                .orElseThrow();

        assertThat(scroll.advertised()).isTrue();
        assertThat(scroll.implemented()).isTrue();
        assertThat(scroll.availability()).isEqualTo(AVAILABLE);
        assertThat(scroll.requiredMethods()).containsExactlyInAnyOrder("GET", "DELETE");
        assertThat(scroll.advertisedMethods()).containsExactlyInAnyOrder("GET", "DELETE");
        assertThat(scroll.methodMetadataKnown()).isTrue();
        assertThat(scroll.condition()).isNull();
    }

    @Test
    void productionBackendRejectsKnownIncompatibleFlipWithoutCallingIt() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.getForObject(BASE + "/ccapi", Map.class))
                .thenReturn(catalog(false));
        CanonCcapiProductionBackend backend = new CanonCcapiProductionBackend(
                PROFILE, BASE, restTemplate);

        assertThat(backend.supports(Operation.LIVE_VIEW)).isFalse();
        assertThatThrownBy(backend::getLiveView)
                .isInstanceOfSatisfying(CameraOperationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(METHOD_NOT_ALLOWED));
        verify(restTemplate, never()).getForObject(FLIP, byte[].class);
        verify(restTemplate, never()).exchange(
                eq(CONTROL),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class));
    }

    private static Map<String, Object> catalog(boolean flipGet) {
        return Map.of("ver100", List.of(
                descriptor("/ccapi/ver100/shooting/liveview", false, true, false),
                descriptor("/ccapi/ver100/shooting/liveview/flip", flipGet, !flipGet, false),
                descriptor("/ccapi/ver100/shooting/liveview/scroll", flipGet, false, flipGet),
                descriptor("/ccapi/ver100/shooting/liveview/multipart", true, false, true),
                descriptor("/ccapi/ver100/shooting/liveview/rtp", false, true, false)));
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
