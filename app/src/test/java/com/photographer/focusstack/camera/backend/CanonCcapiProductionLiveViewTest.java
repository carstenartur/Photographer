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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    private static final String SCROLL = CONTROL + "/scroll";

    @Test
    void productionBackendUsesCapabilityDrivenLifecycleAndStableBodies() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.getForObject(BASE + "/ccapi", Map.class))
                .thenReturn(catalog(true, true));
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
                .thenReturn(catalog(true, true));
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
    void productionBackendFallsBackToCompatibleScrollWhenFlipIsUnavailable() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.getForObject(BASE + "/ccapi", Map.class))
                .thenReturn(catalog(false, true));
        when(restTemplate.exchange(
                eq(CONTROL),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        CanonCcapiScrollLiveViewReader scrollReader = mock(
                CanonCcapiScrollLiveViewReader.class);
        LiveViewFrame expected = new LiveViewFrame(
                new byte[]{(byte) 0xff, (byte) 0xd8, 9, (byte) 0xff, (byte) 0xd9},
                640,
                480,
                Instant.EPOCH,
                "image/jpeg");
        when(scrollReader.nextFrame(SCROLL)).thenReturn(expected);
        CanonCcapiProductionBackend backend = new CanonCcapiProductionBackend(
                PROFILE, BASE, restTemplate, scrollReader);

        assertThat(backend.supports(Operation.LIVE_VIEW)).isTrue();
        assertThat(backend.getLiveView()).isSameAs(expected);
        backend.stopLiveView();

        verify(scrollReader).nextFrame(SCROLL);
        verify(scrollReader).stop();
        verify(restTemplate, never()).getForObject(FLIP, byte[].class);
    }

    @Test
    void productionBackendRejectsKnownIncompatibleFlipWithoutScrollFallback() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.getForObject(BASE + "/ccapi", Map.class))
                .thenReturn(catalog(false, false));
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

    @Test
    void nestedLiveViewLookalikesCannotAdvertiseOrStartATransport() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        String nested = "/ccapi/ver100/vendor/shooting/liveview";
        when(restTemplate.getForObject(BASE + "/ccapi", Map.class)).thenReturn(Map.of(
                "ver100", List.of(descriptor(nested, false, true, false),
                        descriptor(nested + "/flip", true, false, false),
                        descriptor(nested + "/scroll", true, false, true),
                        descriptor(nested + "/multipart", true, false, true),
                        descriptor(nested + "/rtp", false, true, false))));
        CanonCcapiProductionBackend backend = new CanonCcapiProductionBackend(PROFILE, BASE, restTemplate);

        var snapshot = backend.getCapabilitySnapshot();
        assertThat(snapshot.advertisedEndpoints()).hasSize(5);
        assertThat(snapshot.capabilities()).filteredOn(capability -> capability.id().startsWith("live-view-"))
                .hasSize(5).allSatisfy(capability -> {
                    assertThat(capability.advertised()).isFalse();
                    assertThat(capability.condition()).isEqualTo("ENDPOINT_NOT_ADVERTISED");
                });
        assertThat(backend.supports(Operation.LIVE_VIEW)).isFalse();
        assertThatThrownBy(backend::getLiveView).isInstanceOf(CameraOperationException.class);
        verify(restTemplate, never()).getForObject(BASE + nested + "/flip", byte[].class);
        verify(restTemplate, never()).exchange(eq(BASE + nested), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(String.class));
    }

    @Test
    void usableFrameWithoutAdvertisedControlPostCannotStartLiveView() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.getForObject(BASE + "/ccapi", Map.class)).thenReturn(Map.of(
                "ver100", List.of(descriptor("/ccapi/ver100/shooting/liveview", true, false, false),
                        descriptor("/ccapi/ver100/shooting/liveview/flip", true, false, false))));
        CanonCcapiProductionBackend backend = new CanonCcapiProductionBackend(PROFILE, BASE, restTemplate);

        assertThat(backend.supports(Operation.LIVE_VIEW)).isFalse();
        assertThatThrownBy(backend::getLiveView).isInstanceOfSatisfying(CameraOperationException.class,
                failure -> assertThat(failure.reason()).isEqualTo(METHOD_NOT_ALLOWED));
        verify(restTemplate, never()).getForObject(FLIP, byte[].class);
        verify(restTemplate, never()).exchange(eq(CONTROL), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(String.class));
    }

    @Test
    void newerIncompatibleFlipCannotHideOlderUsableVersionInSnapshot() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.getForObject(BASE + "/ccapi", Map.class)).thenReturn(Map.of(
                "ver100", List.of(descriptor("/ccapi/ver100/shooting/liveview", false, true, false),
                        descriptor("/ccapi/ver100/shooting/liveview/flip", true, false, false)),
                "ver120", List.of(descriptor("/ccapi/ver120/shooting/liveview/flip", false, true, false))));
        CanonCcapiProductionBackend backend = new CanonCcapiProductionBackend(PROFILE, BASE, restTemplate);

        assertThat(backend.supports(Operation.LIVE_VIEW)).isTrue();
        assertThat(backend.getCapabilitySnapshot().capabilities())
                .filteredOn(capability -> capability.id().equals("live-view-frame"))
                .singleElement().satisfies(capability -> {
                    assertThat(capability.availability()).isEqualTo(AVAILABLE);
                    assertThat(capability.advertisedMethods()).containsExactly("GET");
                });
    }

    private static Map<String, Object> catalog(boolean flipGet, boolean scroll) {
        List<Map<String, Object>> descriptors = new ArrayList<>();
        descriptors.add(descriptor(
                "/ccapi/ver100/shooting/liveview", false, true, false));
        descriptors.add(descriptor(
                "/ccapi/ver100/shooting/liveview/flip", flipGet, !flipGet, false));
        if (scroll) {
            descriptors.add(descriptor(
                    "/ccapi/ver100/shooting/liveview/scroll", true, false, true));
        }
        descriptors.add(descriptor(
                "/ccapi/ver100/shooting/liveview/multipart", true, false, true));
        descriptors.add(descriptor(
                "/ccapi/ver100/shooting/liveview/rtp", false, true, false));
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
