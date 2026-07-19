package com.photographer.focusstack.camera.backend;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Internal owner of one profile-scoped CCAPI HTTP client and its credential copy.
 *
 * <p>The transport is deliberately package-private: camera operations continue to
 * depend only on {@link RestTemplate}, while the backend factory owns cleanup.</p>
 */
final class CcapiTransport implements AutoCloseable {

    private final RestTemplate restTemplate;
    private final CloseableHttpClient httpClient;
    private final char[] password;
    private final AtomicBoolean closed = new AtomicBoolean();

    CcapiTransport(
            RestTemplate restTemplate,
            CloseableHttpClient httpClient,
            char[] password) {
        this.restTemplate = Objects.requireNonNull(restTemplate, "restTemplate");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.password = password;
    }

    RestTemplate restTemplate() {
        if (closed.get()) {
            throw new IllegalStateException("CCAPI transport is closed");
        }
        return restTemplate;
    }

    boolean isClosed() {
        return closed.get();
    }

    boolean passwordCleared() {
        if (password == null) {
            return true;
        }
        for (char value : password) {
            if (value != '\0') {
                return false;
            }
        }
        return true;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            httpClient.close();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to close CCAPI transport", exception);
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }
}
