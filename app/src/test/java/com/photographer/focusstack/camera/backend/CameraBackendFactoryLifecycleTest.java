package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.model.CameraProfile;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CameraBackendFactoryLifecycleTest {

    @Test
    void evictionClosesTheProfileTransportAndClearsItsPasswordCopy() throws Exception {
        CameraBackendRegistry registry = emptyRegistry();
        CcapiRestTemplateFactory transportFactory = mock(CcapiRestTemplateFactory.class);
        CloseableHttpClient httpClient = mock(CloseableHttpClient.class);
        CcapiTransport transport = transport(httpClient, "camera-password");
        when(transportFactory.create(any(CameraProfile.class), any(URI.class)))
                .thenReturn(transport);
        CameraBackendFactory factory = factory(registry, transportFactory);

        CameraBackend backend = factory.getBackend(profile("camera-1"));
        assertNotNull(backend);

        factory.evict("camera-1");
        factory.evict("camera-1");

        verify(httpClient, times(1)).close();
        assertTrue(transport.isClosed());
        assertTrue(transport.passwordCleared());
    }

    @Test
    void shutdownClosesEveryCachedProfileTransportExactlyOnce() throws Exception {
        CameraBackendRegistry registry = emptyRegistry();
        CcapiRestTemplateFactory transportFactory = mock(CcapiRestTemplateFactory.class);
        CloseableHttpClient firstClient = mock(CloseableHttpClient.class);
        CloseableHttpClient secondClient = mock(CloseableHttpClient.class);
        CcapiTransport first = transport(firstClient, "first-password");
        CcapiTransport second = transport(secondClient, "second-password");
        when(transportFactory.create(any(CameraProfile.class), any(URI.class)))
                .thenReturn(first, second);
        CameraBackendFactory factory = factory(registry, transportFactory);

        factory.getBackend(profile("camera-1"));
        factory.getBackend(profile("camera-2"));
        factory.close();
        factory.close();

        verify(firstClient, times(1)).close();
        verify(secondClient, times(1)).close();
        assertTrue(first.passwordCleared());
        assertTrue(second.passwordCleared());
    }

    private static CameraBackendFactory factory(
            CameraBackendRegistry registry,
            CcapiRestTemplateFactory transportFactory) {
        return new CameraBackendFactory(0, 0, 0, 0, 0, registry, transportFactory);
    }

    private static CameraBackendRegistry emptyRegistry() {
        CameraBackendRegistry registry = mock(CameraBackendRegistry.class);
        when(registry.findByType(any(String.class))).thenReturn(Optional.empty());
        return registry;
    }

    private static CcapiTransport transport(
            CloseableHttpClient httpClient,
            String password) {
        return new CcapiTransport(
                new RestTemplate(),
                httpClient,
                password.toCharArray());
    }

    private static CameraProfile profile(String id) {
        return new CameraProfile(
                id,
                "Test camera " + id,
                "canon-ccapi",
                "http://127.0.0.1:8080",
                1,
                20,
                0.1);
    }
}
