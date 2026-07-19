package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.app.config.CameraConfig;
import com.photographer.focusstack.app.config.CcapiAuthenticationProperties;
import com.photographer.focusstack.common.model.CameraProfile;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CcapiTransportFingerprintTest {

    @Test
    void equivalentCameraOriginsProduceTheSameFingerprint() {
        CcapiAuthenticationProperties properties = new CcapiAuthenticationProperties();
        CcapiRestTemplateFactory factory = factory(properties);
        CameraProfile profile = profile("camera");

        CcapiTransportFingerprint implicitPort = factory.fingerprint(
                profile, URI.create("HTTP://Example.COM/"));
        CcapiTransportFingerprint explicitPort = factory.fingerprint(
                profile, URI.create("http://example.com:80"));

        assertEquals(implicitPort, explicitPort);
        assertEquals(URI.create("http://example.com:80"), implicitPort.baseUri());
    }

    @Test
    void passwordMutationChangesTheFingerprintWithoutPuttingPasswordInIt() {
        CcapiAuthenticationProperties properties = new CcapiAuthenticationProperties();
        CcapiAuthenticationProperties.Credential credential = credential(
                "camera-user", "first-secret");
        properties.setCredentials(Map.of("camera", credential));
        CcapiRestTemplateFactory factory = factory(properties);
        CameraProfile profile = profile("camera");
        URI baseUri = URI.create("http://127.0.0.1:8080");
        CcapiTransportFingerprint before = factory.fingerprint(profile, baseUri);

        credential.setPassword("second-secret");
        CcapiTransportFingerprint after = factory.fingerprint(profile, baseUri);

        assertNotEquals(before, after);
        assertFalse(after.toString().contains("first-secret"));
        assertFalse(after.toString().contains("second-secret"));
        assertFalse(after.toString().contains("camera-user"));
    }

    @Test
    void credentialRemovalChangesConfiguredState() {
        CcapiAuthenticationProperties properties = new CcapiAuthenticationProperties();
        properties.setCredentials(Map.of("camera", credential("user", "password")));
        CcapiRestTemplateFactory factory = factory(properties);
        URI baseUri = URI.create("http://127.0.0.1:8080");
        CcapiTransportFingerprint configured = factory.fingerprint(profile("camera"), baseUri);

        properties.setCredentials(Map.of());
        CcapiTransportFingerprint removed = factory.fingerprint(profile("camera"), baseUri);

        assertNotEquals(configured, removed);
        assertFalse(removed.credentialState().credentialsConfigured());
    }

    @Test
    void rejectsCredentialBearingOrAmbiguousBaseUris() {
        CcapiRestTemplateFactory factory = factory(new CcapiAuthenticationProperties());
        CameraProfile profile = profile("camera");

        assertThrows(IllegalArgumentException.class,
                () -> factory.fingerprint(profile, URI.create("http://user:pass@camera:8080")));
        assertThrows(IllegalArgumentException.class,
                () -> factory.fingerprint(profile, URI.create("http://camera:8080?token=value")));
        assertThrows(IllegalArgumentException.class,
                () -> factory.fingerprint(profile, URI.create("ftp://camera:21")));
    }

    private static CcapiRestTemplateFactory factory(
            CcapiAuthenticationProperties properties) {
        return new CcapiRestTemplateFactory(
                new CameraConfig(
                        URI.create("http://127.0.0.1:8080"),
                        false,
                        Duration.ofSeconds(2)),
                properties);
    }

    private static CameraProfile profile(String id) {
        return new CameraProfile(
                id,
                "Test camera",
                "canon-ccapi",
                "http://127.0.0.1:8080",
                1,
                20,
                0.1);
    }

    private static CcapiAuthenticationProperties.Credential credential(
            String username,
            String password) {
        CcapiAuthenticationProperties.Credential credential =
                new CcapiAuthenticationProperties.Credential();
        credential.setUsername(username);
        credential.setPassword(password);
        return credential;
    }
}
