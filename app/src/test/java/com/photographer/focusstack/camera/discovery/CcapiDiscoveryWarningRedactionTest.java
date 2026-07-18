package com.photographer.focusstack.camera.discovery;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CcapiDiscoveryWarningRedactionTest {

    @Test
    void redactsQueriesTokensPasswordsAndAuthorizationValues() {
        String message = CcapiDiscoveryService.safeWarningMessage(new IOException(
                "GET http://127.0.0.1/device.xml?token=topsecret failed "
                        + "password=hunter2 authorization=BearerValue api_key=abc"));

        assertTrue(message.contains("?<redacted>"));
        assertTrue(message.contains("password=<redacted>"));
        assertTrue(message.contains("authorization=<redacted>"));
        assertTrue(message.contains("api_key=<redacted>"));
        assertFalse(message.contains("topsecret"));
        assertFalse(message.contains("hunter2"));
        assertFalse(message.contains("BearerValue"));
    }

    @Test
    void boundsLongMessages() {
        String message = CcapiDiscoveryService.safeWarningMessage(
                new IOException("x".repeat(1_000)));

        assertTrue(message.length() <= 256);
        assertTrue(message.endsWith("…"));
    }

    @Test
    void usesExceptionTypeWhenMessageIsMissing() {
        assertTrue(CcapiDiscoveryService.safeWarningMessage(
                new IOException()).contains("IOException"));
    }
}
