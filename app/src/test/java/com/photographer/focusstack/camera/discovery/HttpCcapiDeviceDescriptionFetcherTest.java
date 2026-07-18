package com.photographer.focusstack.camera.discovery;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpCcapiDeviceDescriptionFetcherTest {

    @Test
    void readsDescriptionAtTheConfiguredLimit() throws Exception {
        byte[] payload = new byte[HttpCcapiDeviceDescriptionFetcher.MAX_DESCRIPTION_BYTES];
        payload[0] = 1;
        payload[payload.length - 1] = 2;

        assertArrayEquals(payload, HttpCcapiDeviceDescriptionFetcher.readLimited(
                new ByteArrayInputStream(payload)));
    }

    @Test
    void rejectsDescriptionBeyondTheConfiguredLimit() {
        byte[] payload = new byte[HttpCcapiDeviceDescriptionFetcher.MAX_DESCRIPTION_BYTES + 1];

        IOException exception = assertThrows(IOException.class,
                () -> HttpCcapiDeviceDescriptionFetcher.readLimited(
                        new ByteArrayInputStream(payload)));

        assertTrue(exception.getMessage().contains("1 MiB"));
    }

    @Test
    void validatesOnlyAbsoluteHttpUrisWithoutCredentials() {
        assertDoesNotThrow(() -> HttpCcapiDeviceDescriptionFetcher.validateUri(
                URI.create("http://192.168.1.2:49152/device.xml")));
        assertDoesNotThrow(() -> HttpCcapiDeviceDescriptionFetcher.validateUri(
                URI.create("https://[::1]/device.xml")));

        assertThrows(IllegalArgumentException.class,
                () -> HttpCcapiDeviceDescriptionFetcher.validateUri(
                        URI.create("/device.xml")));
        assertThrows(IllegalArgumentException.class,
                () -> HttpCcapiDeviceDescriptionFetcher.validateUri(
                        URI.create("file:///tmp/device.xml")));
        assertThrows(IllegalArgumentException.class,
                () -> HttpCcapiDeviceDescriptionFetcher.validateUri(
                        URI.create("http://user:secret@192.168.1.2/device.xml")));
    }

    @Test
    void rejectsNullInputStream() {
        assertThrows(NullPointerException.class,
                () -> HttpCcapiDeviceDescriptionFetcher.readLimited(null));
    }
}
