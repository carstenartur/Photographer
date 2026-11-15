package com.photographer.focusstack.camera.backend;

import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable normalized view of every endpoint advertised by a Canon CCAPI camera.
 *
 * <p>Canon v1.0 descriptors contain absolute {@code url} values, while v1.1+
 * descriptors contain relative {@code path} values. Cameras may advertise
 * several version groups at once; all groups are retained. The original value,
 * normalized path, absolute URL and method metadata remain available so callers
 * can make exact capability decisions without substring heuristics.</p>
 */
public final class CcapiEndpointCatalog {

    private static final String API_ROOT = "/ccapi";
    private static final Pattern VERSIONED_OPERATION = Pattern.compile("/ccapi/ver[0-9]+(/.*)");
    private static final String LIVE_VIEW = "/shooting/liveview";
    private static final String DEVELOPER_ROOT = "/ccapi/ver100/topurlfordev";
    private static final Pattern VERSION_ROOT_PATTERN = Pattern.compile(".*/ver\\d+/?$");
    private static final Pattern VERSION_NUMBER_PATTERN = Pattern.compile("ver(\\d+)");

    private final List<Endpoint> endpoints;

    private CcapiEndpointCatalog(List<Endpoint> endpoints) {
        this.endpoints = List.copyOf(endpoints);
    }

    /** Discovers and normalizes the endpoint catalog using documented CCAPI roots. */
    public static CcapiEndpointCatalog discover(String baseUrl, RestTemplate restTemplate) {
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        Objects.requireNonNull(restTemplate, "restTemplate");

        Map<String, Object> versions = getMap(restTemplate, normalizedBaseUrl + API_ROOT);
        if (versions == null || versions.keySet().stream().noneMatch(key -> key.startsWith("ver"))) {
            versions = getMap(restTemplate, normalizedBaseUrl + DEVELOPER_ROOT);
        }
        if (versions == null) {
            return new CcapiEndpointCatalog(List.of());
        }

        Builder builder = new Builder(normalizedBaseUrl, restTemplate);
        versions.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("ver"))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> builder.registerVersion(entry.getKey(), entry.getValue()));
        return builder.build();
    }

    /** Every normalized endpoint, ordered by API version and path. */
    public List<Endpoint> endpoints() {
        return endpoints;
    }

    /**
     * Returns the highest-version endpoint whose normalized path ends with the
     * exact suffix. A sibling such as {@code currentstorage} cannot match
     * {@code /devicestatus/storage}.
     */
    public Optional<Endpoint> findExactPath(String suffix) {
        String normalizedSuffix = normalizeSuffix(suffix);
        return endpoints.stream()
                .filter(endpoint -> matchesOperation(endpoint.path(), normalizedSuffix))
                .max(Comparator.comparingInt(Endpoint::versionNumber)
                        .thenComparing(Endpoint::path));
    }

    /** Same as {@link #findExactPath(String)}, additionally requiring a method when known. */
    public Optional<Endpoint> findExactPath(String suffix, HttpMethod method) {
        Objects.requireNonNull(method, "method");
        return findExactPathCandidates(suffix).stream()
                .filter(endpoint -> !endpoint.methodMetadataKnown() || endpoint.methods().contains(method))
                .max(Comparator.comparingInt(Endpoint::versionNumber)
                        .thenComparing(Endpoint::path));
    }

    private List<Endpoint> findExactPathCandidates(String suffix) {
        String normalizedSuffix = normalizeSuffix(suffix);
        return endpoints.stream()
                .filter(endpoint -> matchesOperation(endpoint.path(), normalizedSuffix))
                .toList();
    }

    /** One advertised endpoint with normalized transport metadata. */
    public record Endpoint(
            String version,
            int versionNumber,
            String advertisedValue,
            String path,
            String url,
            Set<HttpMethod> methods,
            boolean methodMetadataKnown
    ) {
        public Endpoint {
            version = Objects.requireNonNull(version, "version");
            advertisedValue = Objects.requireNonNull(advertisedValue, "advertisedValue");
            path = Objects.requireNonNull(path, "path");
            url = Objects.requireNonNull(url, "url");
            methods = methods == null || methods.isEmpty()
                    ? Set.of() : Set.copyOf(methods);
        }
    }

    private static final class Builder {
        private final String baseUrl;
        private final RestTemplate restTemplate;
        private final Map<String, Endpoint> endpointsByIdentity = new LinkedHashMap<>();

        private Builder(String baseUrl, RestTemplate restTemplate) {
            this.baseUrl = baseUrl;
            this.restTemplate = restTemplate;
        }

        private void registerVersion(String version, Object value) {
            if (value instanceof List<?> list) {
                registerList(version, list);
            } else if (value instanceof String string) {
                registerVersionRoot(version, string);
            }
        }

        private void registerList(String version, List<?> list) {
            if (list.isEmpty()) {
                return;
            }
            if (list.getFirst() instanceof Map<?, ?>) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> descriptor) {
                        registerDescriptor(version, descriptor);
                    }
                }
                return;
            }
            if (list.size() == 1 && list.getFirst() instanceof String string
                    && VERSION_ROOT_PATTERN.matcher(endpointPath(string)).matches()) {
                registerVersionRoot(version, string);
                return;
            }
            for (Object item : list) {
                if (item instanceof String string) {
                    registerEndpoint(version, string, Set.of(), false);
                }
            }
        }

        private void registerDescriptor(String version, Map<?, ?> descriptor) {
            Object value = descriptor.get("url");
            if (!(value instanceof String)) {
                value = descriptor.get("path");
            }
            if (!(value instanceof String endpoint)) {
                return;
            }

            Set<HttpMethod> methods = new HashSet<>();
            boolean metadataKnown = false;
            metadataKnown |= addMethod(descriptor, "get", HttpMethod.GET, methods);
            metadataKnown |= addMethod(descriptor, "post", HttpMethod.POST, methods);
            metadataKnown |= addMethod(descriptor, "put", HttpMethod.PUT, methods);
            metadataKnown |= addMethod(descriptor, "delete", HttpMethod.DELETE, methods);
            registerEndpoint(version, endpoint, methods, metadataKnown);
        }

        private boolean addMethod(Map<?, ?> descriptor, String key, HttpMethod method,
                                  Set<HttpMethod> methods) {
            if (!descriptor.containsKey(key)) {
                return false;
            }
            if (Boolean.TRUE.equals(descriptor.get(key))) {
                methods.add(method);
            }
            return true;
        }

        private void registerVersionRoot(String version, String root) {
            String url = absoluteUrl(baseUrl, root);
            try {
                Object response = restTemplate.getForObject(url, Object.class);
                if (response instanceof List<?> list) {
                    registerList(version, list);
                } else if (response instanceof Map<?, ?> map) {
                    Object versionValue = map.get(version);
                    if (versionValue != null) {
                        registerVersion(version, versionValue);
                    } else {
                        for (Object value : map.values()) {
                            if (value instanceof List<?> list) {
                                registerList(version, list);
                            }
                        }
                    }
                }
            } catch (RestClientException ignored) {
                return;
            }
        }

        private void registerEndpoint(String version, String advertisedValue,
                                      Set<HttpMethod> methods, boolean metadataKnown) {
            if (advertisedValue == null || advertisedValue.isBlank()) {
                return;
            }
            String path = endpointPath(advertisedValue);
            Endpoint endpoint = new Endpoint(
                    version,
                    versionNumber(version),
                    advertisedValue,
                    path,
                    absoluteUrl(baseUrl, advertisedValue),
                    methods,
                    metadataKnown
            );
            endpointsByIdentity.put(version + "\n" + path, endpoint);
        }

        private CcapiEndpointCatalog build() {
            List<Endpoint> ordered = new ArrayList<>(endpointsByIdentity.values());
            ordered.sort(Comparator.comparingInt(Endpoint::versionNumber)
                    .thenComparing(Endpoint::path));
            return new CcapiEndpointCatalog(ordered);
        }
    }

    private static Map<String, Object> getMap(RestTemplate restTemplate, String url) {
        try {
            Map<?, ?> response = restTemplate.getForObject(url, Map.class);
            if (response == null || response.isEmpty()) {
                return null;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            response.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        } catch (RestClientException exception) {
            return null;
        }
    }

    private static String endpointPath(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            String path = uri.getPath();
            return path == null ? endpoint : path;
        } catch (IllegalArgumentException exception) {
            int query = endpoint.indexOf('?');
            return query >= 0 ? endpoint.substring(0, query) : endpoint;
        }
    }

    private static String absoluteUrl(String baseUrl, String endpoint) {
        if (endpoint.startsWith("http://") || endpoint.startsWith("https://")) {
            return endpoint;
        }
        return endpoint.startsWith("/") ? baseUrl + endpoint : baseUrl + "/" + endpoint;
    }

    private static int versionNumber(String version) {
        Matcher matcher = VERSION_NUMBER_PATTERN.matcher(version);
        if (!matcher.find()) {
            return -1;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    static boolean matchesOperation(String path, String operation) {
        if (!isLiveViewOperation(operation)) {
            // Older capture/status callers use short suffixes such as "shutterbutton".
            return path.endsWith(operation);
        }
        Matcher versioned = VERSIONED_OPERATION.matcher(path);
        return path.equals(operation) || versioned.matches() && versioned.group(1).equals(operation);
    }

    static boolean isLiveViewOperation(String operation) {
        return operation.equals(LIVE_VIEW) || operation.startsWith(LIVE_VIEW + "/");
    }

    private static String normalizeSuffix(String suffix) {
        Objects.requireNonNull(suffix, "suffix");
        String result = suffix.trim();
        return result.startsWith("/") ? result : "/" + result;
    }

    private static String normalizeBaseUrl(String baseUrl) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        String result = baseUrl.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
