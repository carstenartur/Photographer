package com.photographer.focusstack.camera.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.photographer.focusstack.camera.exception.CameraOperationException;
import com.photographer.focusstack.common.camera.CameraMediaReference;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestTemplate;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.CANCELLED;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.CAMERA_REJECTED;

/** Parses Canon's chunked binary monitoring frames with bounded memory and cancellation. */
final class CanonCcapiMonitoringClient {

    private static final int START_FIRST = 0xFF;
    private static final int START_SECOND = 0x00;
    private static final int EVENT_DATA_TYPE = 0x02;
    private static final int END_FIRST = 0xFF;
    private static final int END_SECOND = 0xFF;
    private static final int MAX_EVENT_BYTES = 4 * 1024 * 1024;
    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(5);
    private static final Pattern VERSION_PATTERN = Pattern.compile("(?:^|/)ver(\\d+)(?:/|$)");
    private static final Pattern MEDIA_PATH_PATTERN =
            Pattern.compile("^/ccapi/ver\\d+/contents/.+\\.[^/]+$");

    private final String profileId;
    private final URI origin;
    private final RestTemplate restTemplate;
    private final Supplier<CcapiEndpointCatalog> catalog;
    private final ObjectMapper objectMapper = new ObjectMapper();

    CanonCcapiMonitoringClient(
            String profileId,
            String baseUrl,
            RestTemplate restTemplate,
            Supplier<CcapiEndpointCatalog> catalog) {
        this.profileId = requireProfileId(profileId);
        this.origin = origin(URI.create(Objects.requireNonNull(baseUrl, "baseUrl")));
        this.restTemplate = Objects.requireNonNull(restTemplate, "restTemplate");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    Optional<CameraMediaReference> awaitAddedMedia(Duration timeout) {
        validateTimeout(timeout);
        checkCancelled();
        String endpoint = CcapiMediaOperation.MONITORING.requireUrl(catalog.get(), profileId);
        ExecutorService executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("ccapi-monitoring-", 0).factory());
        Future<Optional<CameraMediaReference>> future = executor.submit(
                () -> readMonitoring(endpoint));
        boolean restoreInterrupt = false;
        try {
            return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            return Optional.empty();
        } catch (InterruptedException exception) {
            restoreInterrupt = true;
            future.cancel(true);
            throw cancelled(exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new CameraOperationException(profileId, CAMERA_REJECTED, null, cause);
        } finally {
            cancelEvent(endpoint);
            future.cancel(true);
            executor.shutdownNow();
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private Optional<CameraMediaReference> readMonitoring(String endpoint) {
        Optional<CameraMediaReference> result = restTemplate.execute(
                URI.create(endpoint),
                HttpMethod.GET,
                null,
                response -> readFrames(response.getBody()));
        return result == null ? Optional.empty() : result;
    }

    private Optional<CameraMediaReference> readFrames(InputStream input) throws IOException {
        while (true) {
            checkCancelled();
            byte[] json = readFrame(input);
            if (json == null) {
                return Optional.empty();
            }
            JsonNode event;
            try {
                event = objectMapper.readTree(json);
            } catch (IOException | RuntimeException exception) {
                throw rejected(exception);
            }
            if (event == null || !event.isObject()) {
                throw rejected(null);
            }
            JsonNode added = event.get("addedcontents");
            if (added == null || !added.isArray()) {
                continue;
            }
            for (JsonNode value : added) {
                if (!value.isTextual()) {
                    continue;
                }
                Optional<CameraMediaReference> reference = reference(value.textValue());
                if (reference.isPresent()) {
                    return reference;
                }
            }
        }
    }

    /** Returns one JSON payload, or {@code null} at a clean end of stream. */
    private byte[] readFrame(InputStream input) throws IOException {
        int first = readByte(input, true);
        if (first < 0) {
            return null;
        }
        int second = readByte(input, false);
        int dataType = readByte(input, false);
        if (first != START_FIRST || second != START_SECOND || dataType != EVENT_DATA_TYPE) {
            throw rejected(null);
        }

        long size = 0L;
        for (int index = 0; index < Integer.BYTES; index++) {
            size = (size << 8) | readByte(input, false);
        }
        if (size > MAX_EVENT_BYTES) {
            throw rejected(null);
        }
        byte[] data = readExact(input, (int) size);
        int endFirst = readByte(input, false);
        int endSecond = readByte(input, false);
        if (endFirst != END_FIRST || endSecond != END_SECOND) {
            throw rejected(null);
        }
        return data;
    }

    private byte[] readExact(InputStream input, int length) throws IOException {
        byte[] result = new byte[length];
        int offset = 0;
        while (offset < length) {
            checkCancelled();
            int read = input.read(result, offset, length - offset);
            if (read < 0) {
                throw new EOFException("Unexpected end of CCAPI monitoring frame");
            }
            if (read > 0) {
                offset += read;
            }
        }
        return result;
    }

    private int readByte(InputStream input, boolean allowCleanEof) throws IOException {
        checkCancelled();
        int value = input.read();
        if (value < 0 && !allowCleanEof) {
            throw new EOFException("Unexpected end of CCAPI monitoring frame");
        }
        return value;
    }

    private Optional<CameraMediaReference> reference(String value) {
        URI resolved = resolveCameraValue(value);
        String path = resolved.getPath();
        if (!MEDIA_PATH_PATTERN.matcher(path).matches()) {
            return Optional.empty();
        }
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        return Optional.of(new CameraMediaReference(
                storage(path), path, fileName, null, version(path)));
    }

    private void cancelEvent(String endpoint) {
        try {
            restTemplate.delete(endpoint);
        } catch (RuntimeException ignored) {
            // Best-effort protocol cancellation must not replace the original outcome.
        }
    }

    private URI resolveCameraValue(String value) {
        Objects.requireNonNull(value, "camera value");
        URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (IllegalArgumentException exception) {
            throw rejected(null);
        }
        URI resolved = uri.isAbsolute() ? uri : origin.resolve(uri);
        String path = resolved.getPath();
        URI normalized = resolved.normalize();
        if (resolved.getUserInfo() != null
                || resolved.getQuery() != null
                || resolved.getFragment() != null
                || !sameOrigin(origin, resolved)
                || path == null
                || !path.startsWith("/ccapi/")
                || path.indexOf('\\') >= 0
                || hasDotSegment(path)
                || !Objects.equals(path, normalized.getPath())) {
            throw rejected(null);
        }
        return resolved;
    }

    private static boolean hasDotSegment(String path) {
        for (String segment : path.split("/", -1)) {
            if (".".equals(segment) || "..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private static URI origin(URI uri) {
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) {
            throw new IllegalArgumentException("baseUrl must contain a scheme and host");
        }
        try {
            return new URI(
                    scheme.toLowerCase(Locale.ROOT),
                    null,
                    host.toLowerCase(Locale.ROOT),
                    effectivePort(uri),
                    "/",
                    null,
                    null);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("invalid camera base URL", exception);
        }
    }

    private static boolean sameOrigin(URI expected, URI actual) {
        return expected.getScheme().equalsIgnoreCase(actual.getScheme())
                && expected.getHost().equalsIgnoreCase(actual.getHost())
                && effectivePort(expected) == effectivePort(actual);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String storage(String path) {
        String marker = "/contents/";
        int start = path.indexOf(marker) + marker.length();
        int end = path.indexOf('/', start);
        return end < 0 ? path.substring(start) : path.substring(start, end);
    }

    private static String version(String path) {
        Matcher matcher = VERSION_PATTERN.matcher(path);
        return matcher.find() ? "ver" + matcher.group(1) : "unknown";
    }

    private void validateTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero()
                || timeout.isNegative()
                || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException(
                    "timeout must be positive and no longer than " + MAX_TIMEOUT);
        }
    }

    private void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw cancelled(null);
        }
    }

    private CameraOperationException cancelled(Throwable cause) {
        return new CameraOperationException(profileId, CANCELLED, null, cause);
    }

    private CameraOperationException rejected(Throwable cause) {
        return new CameraOperationException(profileId, CAMERA_REJECTED, null, cause);
    }

    private static String requireProfileId(String value) {
        Objects.requireNonNull(value, "profileId");
        if (value.isBlank()) {
            throw new IllegalArgumentException("profileId must not be blank");
        }
        return value;
    }

}
