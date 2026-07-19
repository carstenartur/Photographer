package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.camera.exception.CameraOperationException;
import com.photographer.focusstack.common.camera.CameraMediaProvider;
import com.photographer.focusstack.common.camera.CameraMediaReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.ENDPOINT_NOT_FOUND;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.METHOD_NOT_ALLOWED;

/**
 * Version-aware, bounded CCAPI media and event client.
 *
 * <p>The client follows only same-origin paths returned by the camera, keeps public
 * references relative and never performs media delete or modify operations.</p>
 */
final class CanonCcapiMediaClient {

    private static final Pattern VERSION_PATTERN = Pattern.compile("(?:^|/)ver(\\d+)(?:/|$)");
    private static final Pattern CONTENT_RANGE_PATTERN =
            Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", Pattern.CASE_INSENSITIVE);
    private static final int MAX_TRANSFER_BYTES = 16 * 1024 * 1024;
    private static final int MAX_DIRECTORIES = 100;
    private static final Duration MAX_OBSERVATION_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration POLL_DELAY = Duration.ofMillis(250);

    private final String profileId;
    private final URI origin;
    private final RestTemplate restTemplate;
    private final Supplier<CcapiEndpointCatalog> catalogSupplier;

    CanonCcapiMediaClient(
            String profileId,
            String baseUrl,
            RestTemplate restTemplate,
            Supplier<CcapiEndpointCatalog> catalogSupplier) {
        this.profileId = requireProfileId(profileId);
        this.origin = origin(URI.create(Objects.requireNonNull(baseUrl, "baseUrl")));
        this.restTemplate = Objects.requireNonNull(restTemplate, "restTemplate");
        this.catalogSupplier = Objects.requireNonNull(catalogSupplier, "catalogSupplier");
    }

    CameraMediaProvider.MediaPage listMedia(CameraMediaProvider.MediaQuery query) {
        Objects.requireNonNull(query, "query");
        checkCancelled();
        CcapiEndpointCatalog.Endpoint contents = requiredEndpoint(
                "/contents", HttpMethod.GET, false);

        List<String> storages = listPaths(contents.url());
        List<DirectoryQuery> directories = new ArrayList<>();
        boolean directoryLimitReached = false;
        for (String storage : storages) {
            checkCancelled();
            for (String directory : listPaths(resolveCameraValue(storage).toString())) {
                if (directories.size() >= MAX_DIRECTORIES) {
                    directoryLimitReached = true;
                    break;
                }
                for (String format : cameraFormats(query.formats())) {
                    directories.add(count(directory, format));
                }
            }
            if (directoryLimitReached) {
                break;
            }
        }

        Integer totalAvailable = directoryLimitReached ? null : directories.stream()
                .mapToInt(DirectoryQuery::contentsNumber)
                .sum();
        LinkedHashMap<String, CameraMediaReference> references = new LinkedHashMap<>();
        int pagesVisited = 0;
        boolean truncated = directoryLimitReached;

        outer:
        for (DirectoryQuery directory : directories) {
            int availablePages = directory.pageNumber();
            if (availablePages == 0 && directory.contentsNumber() > 0) {
                availablePages = 1;
            }
            for (int page = 1; page <= availablePages; page++) {
                checkCancelled();
                if (pagesVisited >= query.maxPages()) {
                    truncated = true;
                    break outer;
                }
                pagesVisited++;
                String url = queryUrl(directory.directoryValue(), directory.format(),
                        "list", page);
                for (String value : listPaths(url)) {
                    CameraMediaReference reference = reference(value);
                    if (!query.formats().isEmpty()
                            && !query.formats().contains(reference.format())) {
                        continue;
                    }
                    references.putIfAbsent(reference.relativePath(), reference);
                    if (references.size() >= query.maxItems()) {
                        truncated = totalAvailable == null
                                || references.size() < totalAvailable
                                || page < availablePages;
                        break outer;
                    }
                }
            }
        }

        return new CameraMediaProvider.MediaPage(
                List.copyOf(references.values()), totalAvailable, truncated);
    }

    CameraMediaProvider.MediaChunk downloadMedia(
            CameraMediaReference reference,
            long offset,
            int maxBytes) {
        Objects.requireNonNull(reference, "reference");
        if (offset < 0L) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        if (maxBytes < 1 || maxBytes > MAX_TRANSFER_BYTES) {
            throw new IllegalArgumentException(
                    "maxBytes must be between 1 and " + MAX_TRANSFER_BYTES);
        }
        checkCancelled();
        URI uri = resolveCameraValue(reference.relativePath());
        long end = Math.addExact(offset, maxBytes - 1L);

        return restTemplate.execute(
                uri,
                HttpMethod.GET,
                request -> request.getHeaders().setRange(
                        List.of(HttpRange.createByteRange(offset, end))),
                response -> extractChunk(reference, offset, maxBytes, response));
    }

    Optional<CameraMediaReference> awaitAddedMedia(
            CameraMediaProvider.EventMode mode,
            Duration timeout) {
        Objects.requireNonNull(mode, "mode");
        validateTimeout(timeout);
        checkCancelled();

        String suffix = mode == CameraMediaProvider.EventMode.MONITORING
                ? "/event/monitoring" : "/event/polling";
        boolean requiresDelete = mode != CameraMediaProvider.EventMode.POLLING;
        CcapiEndpointCatalog.Endpoint endpoint = requiredEndpoint(
                suffix, HttpMethod.GET, requiresDelete);
        String requestUrl = mode == CameraMediaProvider.EventMode.POLLING_CONTINUATION
                ? appendQuery(endpoint.url(), "continue=on")
                : endpoint.url();
        long deadline = System.nanoTime() + timeout.toNanos();

        try {
            while (System.nanoTime() < deadline) {
                checkCancelled();
                Duration remaining = Duration.ofNanos(Math.max(1L, deadline - System.nanoTime()));
                Object event = boundedGet(requestUrl, remaining);
                Optional<CameraMediaReference> media = extractMediaValues(event).stream()
                        .map(this::reference)
                        .findFirst();
                if (media.isPresent()) {
                    return media;
                }
                if (mode != CameraMediaProvider.EventMode.POLLING) {
                    continue;
                }
                sleepUntilNextPoll(deadline);
            }
            return Optional.empty();
        } finally {
            if (requiresDelete) {
                cancelEvent(endpoint);
            }
        }
    }

    private DirectoryQuery count(String directoryValue, String format) {
        String url = queryUrl(directoryValue, format, "number", null);
        @SuppressWarnings("unchecked")
        Map<String, Object> response = restTemplate.getForObject(url, Map.class);
        int contentsNumber = nonNegativeInt(response == null ? null : response.get("contentsnumber"));
        int pageNumber = nonNegativeInt(response == null ? null : response.get("pagenumber"));
        return new DirectoryQuery(directoryValue, format, contentsNumber, pageNumber);
    }

    @SuppressWarnings("unchecked")
    private List<String> listPaths(String url) {
        checkCancelled();
        Map<String, Object> response = restTemplate.getForObject(url, Map.class);
        if (response == null) {
            return List.of();
        }
        Object values = response.containsKey("path") ? response.get("path") : response.get("url");
        if (!(values instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object value : list) {
            if (value instanceof String string && !string.isBlank()) {
                resolveCameraValue(string);
                result.add(string);
            }
        }
        return List.copyOf(result);
    }

    private CameraMediaProvider.MediaChunk extractChunk(
            CameraMediaReference reference,
            long requestedOffset,
            int maxBytes,
            org.springframework.http.client.ClientHttpResponse response) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        if (status.value() != HttpStatus.OK.value()
                && status.value() != HttpStatus.PARTIAL_CONTENT.value()) {
            throw new CameraOperationException(
                    profileId, CAMERA_REJECTED, status.value());
        }

        ContentRange range = parseContentRange(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
        if (status.value() == HttpStatus.PARTIAL_CONTENT.value()) {
            if (range == null || range.start() != requestedOffset) {
                throw new CameraOperationException(
                        profileId, CAMERA_REJECTED, status.value());
            }
        } else if (requestedOffset > 0L) {
            throw new CameraOperationException(
                    profileId, CAMERA_REJECTED, status.value());
        }

        BoundedBytes bounded = readBounded(response.getBody(), maxBytes);
        Long totalSize = range == null ? contentLength(response.getHeaders()) : range.totalSize();
        boolean complete = totalSize != null
                ? requestedOffset + bounded.data().length >= totalSize
                : status.value() == HttpStatus.OK.value() && !bounded.overflow();
        return new CameraMediaProvider.MediaChunk(
                reference, requestedOffset, bounded.data(), totalSize, complete);
    }

    private Object boundedGet(String url, Duration remaining) {
        ExecutorService executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("ccapi-event-", 0).factory());
        Future<Object> future = executor.submit(
                () -> restTemplate.getForObject(url, Object.class));
        try {
            return future.get(Math.max(1L, remaining.toNanos()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            return null;
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw cancelled(exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new CameraOperationException(profileId, CAMERA_REJECTED, null, cause);
        } finally {
            executor.shutdownNow();
        }
    }

    private void cancelEvent(CcapiEndpointCatalog.Endpoint endpoint) {
        try {
            restTemplate.delete(endpoint.url());
        } catch (RuntimeException ignored) {
            // Cancellation is best-effort and must not replace the original result.
        }
    }

    private void sleepUntilNextPoll(long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0L) {
            return;
        }
        long millis = Math.min(POLL_DELAY.toMillis(),
                Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw cancelled(exception);
        }
    }

    private CcapiEndpointCatalog.Endpoint requiredEndpoint(
            String suffix,
            HttpMethod requiredMethod,
            boolean requireDelete) {
        CcapiEndpointCatalog.Endpoint endpoint = catalogSupplier.get()
                .findExactPath(suffix)
                .orElseThrow(() -> new CameraOperationException(
                        profileId, ENDPOINT_NOT_FOUND, 404));
        if (endpoint.methodMetadataKnown()
                && !endpoint.methods().contains(requiredMethod)) {
            throw new CameraOperationException(profileId, METHOD_NOT_ALLOWED, 405);
        }
        if (requireDelete && (!endpoint.methodMetadataKnown()
                || !endpoint.methods().contains(HttpMethod.DELETE))) {
            throw new CameraOperationException(profileId, METHOD_NOT_ALLOWED, 405);
        }
        return endpoint;
    }

    private String queryUrl(
            String directoryValue,
            String format,
            String kind,
            Integer page) {
        StringBuilder query = new StringBuilder("type=")
                .append(format)
                .append("&kind=")
                .append(kind);
        if (page != null) {
            query.append("&page=").append(page);
        }
        return appendQuery(resolveCameraValue(directoryValue).toString(), query.toString());
    }

    private static String appendQuery(String url, String query) {
        return url + (url.contains("?") ? "&" : "?") + query;
    }

    private List<String> cameraFormats(Set<String> formats) {
        if (formats.isEmpty()) {
            return List.of("all");
        }
        return formats.stream()
                .map(this::cameraFormat)
                .distinct()
                .sorted()
                .toList();
    }

    private String cameraFormat(String format) {
        return switch (format) {
            case "JPG", "JPEG" -> "jpeg";
            case "HEIF", "HIF" -> "hif";
            default -> format.toLowerCase(Locale.ROOT);
        };
    }

    private List<String> extractMediaValues(Object node) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        collectMediaValues(node, result);
        return List.copyOf(result);
    }

    private void collectMediaValues(Object node, Set<String> result) {
        if (node instanceof String value) {
            if (value.contains("/contents/") && hasFileExtension(value)) {
                resolveCameraValue(value);
                result.add(value);
            }
        } else if (node instanceof List<?> list) {
            for (Object value : list) {
                collectMediaValues(value, result);
            }
        } else if (node instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                collectMediaValues(value, result);
            }
        }
    }

    private CameraMediaReference reference(String value) {
        URI resolved = resolveCameraValue(value);
        String path = resolved.getPath();
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        return new CameraMediaReference(
                storage(path), path, fileName, null, version(path));
    }

    private URI resolveCameraValue(String value) {
        Objects.requireNonNull(value, "camera value");
        URI uri = URI.create(value.trim());
        URI resolved = uri.isAbsolute() ? uri : origin.resolve(uri);
        if (!sameOrigin(origin, resolved)) {
            throw new CameraOperationException(profileId, CAMERA_REJECTED, null);
        }
        String path = resolved.getPath();
        if (path == null || !path.startsWith("/ccapi/")) {
            throw new CameraOperationException(profileId, CAMERA_REJECTED, null);
        }
        return resolved;
    }

    private static URI origin(URI uri) {
        int port = effectivePort(uri);
        return URI.create(uri.getScheme().toLowerCase(Locale.ROOT)
                + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port + "/");
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
        int start = path.indexOf(marker);
        if (start < 0) {
            return "unknown";
        }
        start += marker.length();
        int end = path.indexOf('/', start);
        return end < 0 ? path.substring(start) : path.substring(start, end);
    }

    private static String version(String path) {
        Matcher matcher = VERSION_PATTERN.matcher(path);
        return matcher.find() ? "ver" + matcher.group(1) : "unknown";
    }

    private static boolean hasFileExtension(String value) {
        int query = value.indexOf('?');
        String path = query < 0 ? value : value.substring(0, query);
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return dot > slash && dot < path.length() - 1;
    }

    private static int nonNegativeInt(Object value) {
        if (!(value instanceof Number number)) {
            return 0;
        }
        long result = number.longValue();
        if (result <= 0L) {
            return 0;
        }
        return result > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
    }

    private BoundedBytes readBounded(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 64 * 1024));
        byte[] buffer = new byte[Math.min(8192, maxBytes + 1)];
        boolean overflow = false;
        while (output.size() <= maxBytes) {
            checkCancelled();
            int remaining = maxBytes + 1 - output.size();
            int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read < 0) {
                break;
            }
            output.write(buffer, 0, read);
            if (output.size() > maxBytes) {
                overflow = true;
                break;
            }
        }
        byte[] bytes = output.toByteArray();
        if (bytes.length > maxBytes) {
            bytes = java.util.Arrays.copyOf(bytes, maxBytes);
        }
        return new BoundedBytes(bytes, overflow);
    }

    private static ContentRange parseContentRange(String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = CONTENT_RANGE_PATTERN.matcher(value.trim());
        if (!matcher.matches()) {
            return null;
        }
        long start = Long.parseLong(matcher.group(1));
        long end = Long.parseLong(matcher.group(2));
        Long total = "*".equals(matcher.group(3))
                ? null : Long.parseLong(matcher.group(3));
        return new ContentRange(start, end, total);
    }

    private static Long contentLength(HttpHeaders headers) {
        long value = headers.getContentLength();
        return value < 0L ? null : value;
    }

    private void validateTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(MAX_OBSERVATION_TIMEOUT) > 0) {
            throw new IllegalArgumentException(
                    "timeout must be positive and no longer than " + MAX_OBSERVATION_TIMEOUT);
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

    private static String requireProfileId(String value) {
        Objects.requireNonNull(value, "profileId");
        if (value.isBlank()) {
            throw new IllegalArgumentException("profileId must not be blank");
        }
        return value;
    }

    private record DirectoryQuery(
            String directoryValue,
            String format,
            int contentsNumber,
            int pageNumber) {
    }

    private record BoundedBytes(byte[] data, boolean overflow) {
    }

    private record ContentRange(long start, long end, Long totalSize) {
    }
}
