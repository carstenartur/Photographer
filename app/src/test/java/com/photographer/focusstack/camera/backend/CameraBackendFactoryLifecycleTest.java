package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.app.config.CcapiAuthenticationProperties;
import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.model.CameraProfile;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        CcapiTransportFingerprint fingerprint = fingerprint(
                "http://127.0.0.1:8080", true, 1, 2);
        when(transportFactory.fingerprint(any(CameraProfile.class), any(URI.class)))
                .thenReturn(fingerprint);
        when(transportFactory.createWithFingerprint(any(CameraProfile.class), any(URI.class)))
                .thenReturn(new CcapiTransportCreation(transport, fingerprint));
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
        CcapiTransportFingerprint fingerprint = fingerprint(
                "http://127.0.0.1:8080", true, 1, 2);
        when(transportFactory.fingerprint(any(CameraProfile.class), any(URI.class)))
                .thenReturn(fingerprint);
        when(transportFactory.createWithFingerprint(any(CameraProfile.class), any(URI.class)))
                .thenReturn(
                        new CcapiTransportCreation(first, fingerprint),
                        new CcapiTransportCreation(second, fingerprint));
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

    @Test
    void unchangedTransportFingerprintReusesTheSameBackend() throws Exception {
        CameraBackendRegistry registry = emptyRegistry();
        CcapiRestTemplateFactory transportFactory = mock(CcapiRestTemplateFactory.class);
        CloseableHttpClient httpClient = mock(CloseableHttpClient.class);
        CcapiTransport transport = transport(httpClient, "password");
        CcapiTransportFingerprint fingerprint = fingerprint(
                "http://127.0.0.1:8080", true, 1, 2);
        when(transportFactory.fingerprint(any(CameraProfile.class), any(URI.class)))
                .thenReturn(fingerprint);
        when(transportFactory.createWithFingerprint(any(CameraProfile.class), any(URI.class)))
                .thenReturn(new CcapiTransportCreation(transport, fingerprint));
        CameraBackendFactory factory = factory(registry, transportFactory);

        CameraBackend first = factory.getBackend(profile("camera-1"));
        CameraBackend second = factory.getBackend(profile("camera-1"));

        assertSame(first, second);
        verify(transportFactory, times(1))
                .createWithFingerprint(any(CameraProfile.class), any(URI.class));
        verify(httpClient, never()).close();
        factory.close();
    }

    @Test
    void hostChangeAtomicallyReplacesAndClosesTheOldTransport() throws Exception {
        CameraBackendRegistry registry = emptyRegistry();
        CcapiRestTemplateFactory transportFactory = mock(CcapiRestTemplateFactory.class);
        CloseableHttpClient firstClient = mock(CloseableHttpClient.class);
        CloseableHttpClient secondClient = mock(CloseableHttpClient.class);
        CcapiTransport firstTransport = transport(firstClient, "first-password");
        CcapiTransport secondTransport = transport(secondClient, "second-password");
        CcapiTransportFingerprint firstFingerprint = fingerprint(
                "http://127.0.0.1:8080", true, 1, 2);
        CcapiTransportFingerprint secondFingerprint = fingerprint(
                "http://127.0.0.2:8080", true, 1, 2);
        when(transportFactory.fingerprint(any(CameraProfile.class), any(URI.class)))
                .thenReturn(firstFingerprint, secondFingerprint);
        when(transportFactory.createWithFingerprint(any(CameraProfile.class), any(URI.class)))
                .thenReturn(
                        new CcapiTransportCreation(firstTransport, firstFingerprint),
                        new CcapiTransportCreation(secondTransport, secondFingerprint));
        CameraBackendFactory factory = factory(registry, transportFactory);

        CameraBackend first = factory.getBackend(profile("camera-1", "http://127.0.0.1:8080"));
        CameraBackend second = factory.getBackend(profile("camera-1", "http://127.0.0.2:8080"));

        assertNotSame(first, second);
        verify(firstClient, times(1)).close();
        verify(secondClient, never()).close();
        assertTrue(firstTransport.passwordCleared());
        assertFalse(secondTransport.passwordCleared());
        factory.close();
    }

    @Test
    void credentialRevisionChangeReplacesTheBackendWithoutSecretDerivedKeys() throws Exception {
        CameraBackendRegistry registry = emptyRegistry();
        CcapiRestTemplateFactory transportFactory = mock(CcapiRestTemplateFactory.class);
        CloseableHttpClient firstClient = mock(CloseableHttpClient.class);
        CloseableHttpClient secondClient = mock(CloseableHttpClient.class);
        CcapiTransport firstTransport = transport(firstClient, "old-password");
        CcapiTransport secondTransport = transport(secondClient, "new-password");
        CcapiTransportFingerprint firstFingerprint = fingerprint(
                "http://127.0.0.1:8080", true, 1, 2);
        CcapiTransportFingerprint secondFingerprint = fingerprint(
                "http://127.0.0.1:8080", true, 1, 3);
        when(transportFactory.fingerprint(any(CameraProfile.class), any(URI.class)))
                .thenReturn(firstFingerprint, secondFingerprint);
        when(transportFactory.createWithFingerprint(any(CameraProfile.class), any(URI.class)))
                .thenReturn(
                        new CcapiTransportCreation(firstTransport, firstFingerprint),
                        new CcapiTransportCreation(secondTransport, secondFingerprint));
        CameraBackendFactory factory = factory(registry, transportFactory);

        CameraBackend first = factory.getBackend(profile("camera-1"));
        CameraBackend second = factory.getBackend(profile("camera-1"));

        assertNotSame(first, second);
        verify(firstClient, times(1)).close();
        assertTrue(firstTransport.passwordCleared());
        assertFalse(secondFingerprint.toString().contains("old-password"));
        assertFalse(secondFingerprint.toString().contains("new-password"));
        factory.close();
    }

    @Test
    void credentialRemovalReplacesTheAuthenticatedTransport() throws Exception {
        CameraBackendRegistry registry = emptyRegistry();
        CcapiRestTemplateFactory transportFactory = mock(CcapiRestTemplateFactory.class);
        CloseableHttpClient authenticatedClient = mock(CloseableHttpClient.class);
        CloseableHttpClient unauthenticatedClient = mock(CloseableHttpClient.class);
        CcapiTransport authenticated = transport(authenticatedClient, "password");
        CcapiTransport unauthenticated = transport(unauthenticatedClient, null);
        CcapiTransportFingerprint configured = fingerprint(
                "http://127.0.0.1:8080", true, 1, 2);
        CcapiTransportFingerprint removed = fingerprint(
                "http://127.0.0.1:8080", false, 2, 0);
        when(transportFactory.fingerprint(any(CameraProfile.class), any(URI.class)))
                .thenReturn(configured, removed);
        when(transportFactory.createWithFingerprint(any(CameraProfile.class), any(URI.class)))
                .thenReturn(
                        new CcapiTransportCreation(authenticated, configured),
                        new CcapiTransportCreation(unauthenticated, removed));
        CameraBackendFactory factory = factory(registry, transportFactory);

        CameraBackend first = factory.getBackend(profile("camera-1"));
        CameraBackend second = factory.getBackend(profile("camera-1"));

        assertNotSame(first, second);
        verify(authenticatedClient, times(1)).close();
        assertTrue(authenticated.passwordCleared());
        assertTrue(unauthenticated.passwordCleared());
        factory.close();
    }

    @Test
    void failedReplacementKeepsThePreviousBackendOpenAndInstalled() throws Exception {
        CameraBackendRegistry registry = emptyRegistry();
        CcapiRestTemplateFactory transportFactory = mock(CcapiRestTemplateFactory.class);
        CloseableHttpClient firstClient = mock(CloseableHttpClient.class);
        CcapiTransport firstTransport = transport(firstClient, "password");
        CcapiTransportFingerprint firstFingerprint = fingerprint(
                "http://127.0.0.1:8080", true, 1, 2);
        CcapiTransportFingerprint changedFingerprint = fingerprint(
                "http://127.0.0.1:8080", true, 1, 3);
        when(transportFactory.fingerprint(any(CameraProfile.class), any(URI.class)))
                .thenReturn(firstFingerprint, changedFingerprint, firstFingerprint);
        when(transportFactory.createWithFingerprint(any(CameraProfile.class), any(URI.class)))
                .thenReturn(new CcapiTransportCreation(firstTransport, firstFingerprint))
                .thenThrow(new IllegalStateException("synthetic replacement failure"));
        CameraBackendFactory factory = factory(registry, transportFactory);

        CameraBackend first = factory.getBackend(profile("camera-1"));
        assertThrows(IllegalStateException.class,
                () -> factory.getBackend(profile("camera-1")));
        CameraBackend retained = factory.getBackend(profile("camera-1"));

        assertSame(first, retained);
        verify(firstClient, never()).close();
        assertFalse(firstTransport.passwordCleared());
        factory.close();
    }

    @Test
    void concurrentAccessCreatesOnlyOneBackendForTheSameFingerprint() throws Exception {
        CameraBackendRegistry registry = emptyRegistry();
        CcapiRestTemplateFactory transportFactory = mock(CcapiRestTemplateFactory.class);
        CloseableHttpClient httpClient = mock(CloseableHttpClient.class);
        CcapiTransport transport = transport(httpClient, "password");
        CcapiTransportFingerprint fingerprint = fingerprint(
                "http://127.0.0.1:8080", true, 1, 2);
        CountDownLatch creationEntered = new CountDownLatch(1);
        CountDownLatch allowCreation = new CountDownLatch(1);
        when(transportFactory.fingerprint(any(CameraProfile.class), any(URI.class)))
                .thenReturn(fingerprint);
        when(transportFactory.createWithFingerprint(any(CameraProfile.class), any(URI.class)))
                .thenAnswer(invocation -> {
                    creationEntered.countDown();
                    assertTrue(allowCreation.await(5, TimeUnit.SECONDS));
                    return new CcapiTransportCreation(transport, fingerprint);
                });
        CameraBackendFactory factory = factory(registry, transportFactory);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<CameraBackend> first = executor.submit(
                    () -> factory.getBackend(profile("camera-1")));
            assertTrue(creationEntered.await(5, TimeUnit.SECONDS));
            Future<CameraBackend> second = executor.submit(
                    () -> factory.getBackend(profile("camera-1")));
            allowCreation.countDown();

            assertSame(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
        }
        verify(transportFactory, times(1))
                .createWithFingerprint(any(CameraProfile.class), any(URI.class));
        factory.close();
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
                password == null ? null : password.toCharArray());
    }

    private static CcapiTransportFingerprint fingerprint(
            String baseUri,
            boolean configured,
            long configurationRevision,
            long credentialRevision) {
        return new CcapiTransportFingerprint(
                URI.create(baseUri),
                new CcapiAuthenticationProperties.CredentialState(
                        configured, configurationRevision, credentialRevision));
    }

    private static CameraProfile profile(String id) {
        return profile(id, "http://127.0.0.1:8080");
    }

    private static CameraProfile profile(String id, String host) {
        return new CameraProfile(
                id,
                "Test camera " + id,
                "canon-ccapi",
                host,
                1,
                20,
                0.1);
    }
}
