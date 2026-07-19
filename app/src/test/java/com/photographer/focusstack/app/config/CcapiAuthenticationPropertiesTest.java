package com.photographer.focusstack.app.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        try {
            assertEquals("camera-user", resolved.username());
            assertArrayEquals("secret-value".toCharArray(), resolved.passwordCopy());
            assertTrue(properties.resolve("PowerShot G5").isEmpty());
            assertTrue(properties.resolve("192.168.1.2").isEmpty());
        } finally {
            resolved.close();
        }
    }

    @Test
    void secretValuesAreRedactedFromStringRepresentations() {
        CcapiAuthenticationProperties.Credential credential = credential("visible-user", "never-log-me");
        CcapiAuthenticationProperties properties = new CcapiAuthenticationProperties();
        properties.setCredentials(Map.of("camera", credential));
        CcapiAuthenticationProperties.ResolvedCredentials resolved =
                properties.resolve("camera").orElseThrow();
        try {
            assertFalse(credential.toString().contains("visible-user"));
            assertFalse(credential.toString().contains("never-log-me"));
            assertFalse(resolved.toString().contains("visible-user"));
            assertFalse(resolved.toString().contains("never-log-me"));
            assertTrue(resolved.toString().contains("<redacted>"));
        } finally {
            resolved.close();
        }
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

    @Test
    void credentialMutationChangesOnlyTheSecretFreeEntryRevision() {
        CcapiAuthenticationProperties properties = new CcapiAuthenticationProperties();
        CcapiAuthenticationProperties.Credential credential = credential("user", "first-password");
        properties.setCredentials(Map.of("camera", credential));
        CcapiAuthenticationProperties.CredentialState before = properties.state("camera");

        credential.setPassword("second-password");
        CcapiAuthenticationProperties.CredentialState after = properties.state("camera");

        assertTrue(before.credentialsConfigured());
        assertTrue(after.credentialsConfigured());
        assertEquals(before.configurationRevision(), after.configurationRevision());
        assertNotEquals(before.credentialRevision(), after.credentialRevision());
        assertFalse(after.toString().contains("first-password"));
        assertFalse(after.toString().contains("second-password"));
    }

    @Test
    void mapReplacementChangesConfigurationRevisionWithoutPasswordDerivedIdentity() {
        CcapiAuthenticationProperties properties = new CcapiAuthenticationProperties();
        properties.setCredentials(Map.of("camera", credential("user", "first-password")));
        CcapiAuthenticationProperties.CredentialState before = properties.state("camera");

        properties.setCredentials(Map.of("camera", credential("user", "second-password")));
        CcapiAuthenticationProperties.CredentialState after = properties.state("camera");

        assertNotEquals(before.configurationRevision(), after.configurationRevision());
        assertFalse(after.toString().contains("first-password"));
        assertFalse(after.toString().contains("second-password"));
    }

    @Test
    void removingCredentialsProducesANewUnconfiguredState() {
        CcapiAuthenticationProperties properties = new CcapiAuthenticationProperties();
        properties.setCredentials(Map.of("camera", credential("user", "password")));
        CcapiAuthenticationProperties.CredentialState configured = properties.state("camera");

        properties.setCredentials(Map.of());
        CcapiAuthenticationProperties.CredentialState removed = properties.state("camera");

        assertTrue(configured.credentialsConfigured());
        assertFalse(removed.credentialsConfigured());
        assertNotEquals(configured.configurationRevision(), removed.configurationRevision());
    }

    @Test
    void closingResolvedCredentialsMakesFurtherPasswordCopiesImpossible() {
        CcapiAuthenticationProperties properties = new CcapiAuthenticationProperties();
        properties.setCredentials(Map.of("camera", credential("user", "password")));
        CcapiAuthenticationProperties.ResolvedCredentials resolved =
                properties.resolve("camera").orElseThrow();

        resolved.close();
        resolved.close();

        assertThrows(IllegalStateException.class, resolved::passwordCopy);
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
