package com.photographer.focusstack.camera.discovery;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/** Bounded HTTP client for UPnP device descriptions; redirects are not followed. */
final class HttpCcapiDeviceDescriptionFetcher implements CcapiDeviceDescriptionFetcher {

    private static final int MAX_DESCRIPTION_BYTES = 1024 * 1024;
    private final HttpClient httpClient;

    HttpCcapiDeviceDescriptionFetcher() {
        this(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    HttpCcapiDeviceDescriptionFetcher(HttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    @Override
    public byte[] fetch(URI descriptionUrl, Duration timeout)
            throws IOException, InterruptedException {
        validateUri(descriptionUrl);
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }

        HttpRequest request = HttpRequest.newBuilder(descriptionUrl)
                .timeout(timeout)
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            try (InputStream ignored = response.body()) {
                throw new IOException("Device description returned HTTP " + response.statusCode());
            }
        }
        try (InputStream body = response.body()) {
            return readLimited(body);
        }
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(8 * 1024);
        byte[] buffer = new byte[8 * 1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > MAX_DESCRIPTION_BYTES) {
                throw new IOException("Device description exceeds 1 MiB limit");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void validateUri(URI uri) {
        Objects.requireNonNull(uri, "descriptionUrl");
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("descriptionUrl must be an absolute HTTP URI without user info");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("descriptionUrl must use HTTP or HTTPS");
        }
    }
}
