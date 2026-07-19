package com.photographer.focusstack.app.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CcapiAuthenticationPropertiesTest {

    @Test
    void resolvesCredentialsByExactProfileIdOnly() {
        CcapiAuthenticationProperties properties = new CcapiAuthenticationProperties();
        CcapiAuthenticationProperties.Credential credential = credential("camera-user", "secret-value");
        properties.setCredentials(Map.of("powershot-g5", credential));

        CcapiAuthenticationProperties.ResolvedCredentials resolved =
                properties.resolve("powershot-g5").orElseThrow();

        assertEquals("camera-user", resolved.username());
        assertArrayEquals("secret-value".toCharArray(), resolved.passwordCopy());
        assertTrue(properties.resolve("PowerShot G5").isEmpty());
        assertTrue(properties.resolve("192.168.1.2").isEmpty());
    }

    @Test
    void secretValuesAreRedactedFromStringRepresentations() {
        CcapiAuthenticationProperties.Credential credential = credential("visible-user", "never-log-me");
        CcapiAuthenticationProperties properties = new CcapiAuthenticationProperties();
        properties.setCredentials(Map.of("camera", credential));
        CcapiAuthenticationProperties.ResolvedCredentials resolved =
                properties.resolve("camera").orElseThrow();

        assertFalse(credential.toString().contains("visible-user"));
        assertFalse(credential.toString().contains("never-log-me"));
        assertFalse(resolved.toString().contains("visible-user"));
        assertFalse(resolved.toString().contains("never-log-me"));
        assertTrue(resolved.toString().contains("<redacted>"));
    }

    @Test
    void rejectsPartialCredentialConfigurationWithoutIncludingSecretValue() {
        CcapiAuthenticationProperties properties = new CcapiAuthenticationProperties();
        properties.setCredentials(Map.of("camera-a", credential("camera-user", null)));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> properties.resolve("camera-a"));

        assertTrue(failure.getMessage().contains("camera-a"));
        assertFalse(failure.getMessage().contains("camera-user"));
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
