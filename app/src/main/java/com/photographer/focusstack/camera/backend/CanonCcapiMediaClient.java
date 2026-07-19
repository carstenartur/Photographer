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
import java.net.URISyntaxException;
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
 * Version-aware, bounded CCAPI media and polling client.
 *
 * <p>The client follows only normalized same-origin paths returned by the camera,
 * keeps public references relative and never performs media delete or modify
 * operations. Binary monitoring is handled by {@link CanonCcapiMonitoringClient}.</p>
 */
final class CanonCcapiMediaClient {

    private static final Pattern VERSION_PATTERN = Pattern.compile("(?:^|/)ver(\\d+)(?:/|$)");
    private static final Pattern MEDIA_PATH_PATTERN =
            Pattern.compile("^/ccapi/ver\\d+/contents/.+\\.[^/]+$");
    private static final Pattern CONTENT_RANGE_PATTERN =
            Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", Pattern.CASE_INSENSITIVE);
    private static final int MAX_TRANSFER_BYTES = 16 * 1024 * 1024;
    private static final int MAX_DIRECTORIES = 100;
    private static final Duration MAX_OBSERVATION_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration POLL_DELAY = Duration.ofMillis(250);

    private final String profileId;
    private final URI origin;
    private final RestTemplate restTemplate;
    private final Supplier<Map<String, String>> endpointSupplier;
    private final Supplier<Map<String, Set<HttpMethod>>> methodSupplier;

    CanonCcapiMediaClient(
            String profileId,
            String baseUrl,
            RestTemplate restTemplate,
            Supplier<Map<String, String>> endpointSupplier,
            Supplier<Map<String, Set<HttpMethod>>> methodSupplier) {
        this.profileId = requireProfileId(profileId);
        this.origin = origin(URI.create(Objects.requireNonNull(baseUrl, "baseUrl")));
        this.restTemplate = Objects.requireNonNull(restTemplate, "restTemplate");
        this.endpointSupplier = Objects.requireNonNull(endpointSupplier, "endpointSupplier");
        this.methodSupplier = Objects.requireNonNull(methodSupplier, "methodSupplier");
    }

    CameraMediaProvider.MediaPage listMedia(CameraMediaProvider.MediaQuery query) {
        Objects.requireNonNull(query, "query");
        checkCancelled();
        EndpointView contents = requiredEndpoint("/contents", HttpMethod.GET, false);

        List<String> storages = listPaths(contents.url());
        List<DirectoryQuery> directories = new ArrayList<>();
        boolean directoryLimitReached = false;

        storageLoop:
        for (String storage : storages) {
            checkCancelled();
            for (String directory : listPaths(resolveCameraValue(storage).toString())) {
                for (String format : cameraFormats(query.formats())) {
                    if (directories.size() >= MAX_DIRECTORIES) {
                        directoryLimitReached = true;
                        break storageLoop;
                    }
                    directories.add(count(directory, format));
                }
            }
        }

        Integer totalAvailable = directoryLimitReached
                ? null
                : clampedTotal(directories);
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
                    if (!requestedFormat(reference.format(), query.formats())) {
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
        if (offset > Long.MAX_VALUE - maxBytes + 1L) {
            throw new IllegalArgumentException(
                    "requested byte range exceeds the supported offset range");
        }
        checkCancelled();
        URI uri = resolveCameraValue(reference.relativePath());
        long end = offset + maxBytes - 1L;

        CameraMediaProvider.MediaChunk result = restTemplate.execute(
                uri,
                HttpMethod.GET,
                request -> request.getHeaders().setRange(
                        List.of(HttpRange.createByteRange(offset, end))),
                response -> extractChunk(reference, offset, maxBytes, response));
        return Objects.requireNonNull(result, "camera media response");
    }

    Optional<CameraMediaReference> awaitAddedMedia(
            CameraMediaProvider.EventMode mode,
            Duration timeout) {
        Objects.requireNonNull(mode, "mode");
        if (mode == CameraMediaProvider.EventMode.MONITORING) {
            throw new IllegalArgumentException(
                    "binary monitoring must be handled by CanonCcapiMonitoringClient");
        }
        validateTimeout(timeout);
        checkCancelled();

        boolean requiresDelete = mode == CameraMediaProvider.EventMode.POLLING_CONTINUATION;
        EndpointView endpoint = requiredEndpoint("/event/polling", HttpMethod.GET, requiresDelete);
        String requestUrl = requiresDelete
                ? appendQuery(endpoint.url(), "continue=on")
                : endpoint.url();
        long deadline = System.nanoTime() + timeout.toNanos();
        ExecutorService executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("ccapi-event-", 0).factory());

        try {
            while (System.nanoTime() < deadline) {
                checkCancelled();
                Duration remaining = Duration.ofNanos(
                        Math.max(1L, deadline - System.nanoTime()));
                Object event = boundedGet(requestUrl, remaining, executor);
                Optional<CameraMediaReference> media = extractMediaValues(event).stream()
                        .map(this::reference)
                        .findFirst();
                if (media.isPresent()) {
                    return media;
                }
                if (mode == CameraMediaProvider.EventMode.POLLING) {
                    sleepUntilNextPoll(deadline);
                }
            }
            return Optional.empty();
        } finally {
            boolean interrupted = Thread.interrupted();
            if (requiresDelete) {
                cancelEvent(endpoint);
            }
            executor.shutdownNow();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private DirectoryQuery count(String directoryValue, String format) {
        String url = queryUrl(directoryValue, format, "number", null);
        @SuppressWarnings("unchecked")
        Map<String, Object> response = restTemplate.getForObject(url, Map.class);
        int contentsNumber = nonNegativeInt(
                response == null ? null : response.get("contentsnumber"));
        int pageNumber = nonNegativeInt(
                response == null ? null : response.get("pagenumber"));
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
            throw rejected(status.value());
        }

        ContentRange range = parseContentRange(
                response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
        if (status.value() == HttpStatus.PARTIAL_CONTENT.value()) {
            if (range == null || range.start() != requestedOffset) {
                throw rejected(status.value());
            }
        } else if (requestedOffset > 0L) {
            throw rejected(status.value());
        }

        BoundedBytes bounded = readBounded(response.getBody(), maxBytes);
        if (bounded.overflow()) {
            throw rejected(status.value());
        }

        if (range != null) {
            long declaredLength = range.end() - range.start() + 1L;
            long requestedEnd = requestedOffset + maxBytes - 1L;
            if (declaredLength != bounded.data().length || range.end() > requestedEnd) {
                throw rejected(status.value());
            }
        }

        Long totalSize = range == null
                ? contentLength(response.getHeaders())
                : range.totalSize();
        if (status.value() == HttpStatus.OK.value()
                && totalSize != null
                && totalSize != bounded.data().length) {
            throw rejected(status.value());
        }

        boolean complete = range != null && totalSize != null
                ? range.end() + 1L >= totalSize
                : status.value() == HttpStatus.OK.value();
        return new CameraMediaProvider.MediaChunk(
                reference, requestedOffset, bounded.data(), totalSize, complete);
    }

    private Object boundedGet(
            String url,
            Duration remaining,
            ExecutorService executor) {
        Future<Object> future = executor.submit(
                () -> restTemplate.getForObject(url, Object.class));
        try {
            return future.get(
                    Math.max(1L, remaining.toNanos()), TimeUnit.NANOSECONDS);
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
            throw new CameraOperationException(
                    profileId, CAMERA_REJECTED, null, cause);
        }
    }

    private void cancelEvent(EndpointView endpoint) {
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

    private EndpointView requiredEndpoint(
            String suffix,
            HttpMethod requiredMethod,
            boolean requireDelete) {
        Map<String, String> endpoints = endpointSupplier.get();
        Map<String, Set<HttpMethod>> methods = methodSupplier.get();
        List<EndpointView> candidates = endpoints.entrySet().stream()
                .map(entry -> endpoint(entry, methods))
                .filter(candidate -> candidate.path().endsWith(suffix))
                .toList();
        if (candidates.isEmpty()) {
            throw new CameraOperationException(profileId, ENDPOINT_NOT_FOUND, 404);
        }

        return candidates.stream()
                .filter(candidate -> !candidate.methodMetadataKnown()
                        || candidate.methods().contains(requiredMethod))
                .filter(candidate -> !requireDelete
                        || candidate.methodMetadataKnown()
                        && candidate.methods().contains(HttpMethod.DELETE))
                .max(Comparator.comparingInt(EndpointView::versionNumber)
                        .thenComparing(EndpointView::path))
                .orElseThrow(() -> new CameraOperationException(
                        profileId, METHOD_NOT_ALLOWED, 405));
    }

    private EndpointView endpoint(
            Map.Entry<String, String> entry,
            Map<String, Set<HttpMethod>> methods) {
        URI resolved = resolveCameraValue(entry.getValue());
        Set<HttpMethod> advertisedMethods = methods.get(entry.getKey());
        return new EndpointView(
                resolved.getPath(),
                resolved.toString(),
                versionNumber(resolved.getPath()),
                advertisedMethods == null ? Set.of() : Set.copyOf(advertisedMethods),
                advertisedMethods != null);
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

    private boolean requestedFormat(
            String actualFormat,
            Set<String> requestedFormats) {
        if (requestedFormats.isEmpty()) {
            return true;
        }
        String actual = cameraFormat(actualFormat);
        return requestedFormats.stream()
                .map(this::cameraFormat)
                .anyMatch(actual::equals);
    }

    private List<String> extractMediaValues(Object node) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        collectMediaValues(node, result);
        return List.copyOf(result);
    }

    private void collectMediaValues(Object node, Set<String> result) {
        if (node instanceof String value) {
            if (!value.contains("/contents/")) {
                return;
            }
            URI resolved = resolveCameraValue(value);
            if (MEDIA_PATH_PATTERN.matcher(resolved.getPath()).matches()) {
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

    private static int versionNumber(String path) {
        Matcher matcher = VERSION_PATTERN.matcher(path);
        if (!matcher.find()) {
            return -1;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException exception) {
            return -1;
        }
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

    private static Integer clampedTotal(List<DirectoryQuery> directories) {
        long total = 0L;
        for (DirectoryQuery directory : directories) {
            total += directory.contentsNumber();
            if (total >= Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
        }
        return (int) total;
    }

    private BoundedBytes readBounded(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                Math.min(maxBytes, 64 * 1024));
        byte[] buffer = new byte[Math.min(8192, maxBytes + 1)];
        boolean overflow = false;
        while (output.size() <= maxBytes) {
            checkCancelled();
            int remaining = maxBytes + 1 - output.size();
            int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read < 0) {
                break;
            }
            if (read == 0) {
                continue;
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
        try {
            long start = Long.parseLong(matcher.group(1));
            long end = Long.parseLong(matcher.group(2));
            Long total = "*".equals(matcher.group(3))
                    ? null
                    : Long.parseLong(matcher.group(3));
            if (end < start || total != null && total <= end) {
                return null;
            }
            return new ContentRange(start, end, total);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Long contentLength(HttpHeaders headers) {
        long value = headers.getContentLength();
        return value < 0L ? null : value;
    }

    private void validateTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero()
                || timeout.isNegative()
                || timeout.compareTo(MAX_OBSERVATION_TIMEOUT) > 0) {
            throw new IllegalArgumentException(
                    "timeout must be positive and no longer than "
                            + MAX_OBSERVATION_TIMEOUT);
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

    private CameraOperationException rejected(Integer status) {
        return new CameraOperationException(profileId, CAMERA_REJECTED, status);
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

    private record EndpointView(
            String path,
            String url,
            int versionNumber,
            Set<HttpMethod> methods,
            boolean methodMetadataKnown) {
    }

    private record BoundedBytes(byte[] data, boolean overflow) {
    }

    private record ContentRange(long start, long end, Long totalSize) {
    }
}
