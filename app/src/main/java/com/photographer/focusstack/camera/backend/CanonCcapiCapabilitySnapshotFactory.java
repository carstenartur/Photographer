package com.photographer.focusstack.camera.backend;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.photographer.focusstack.camera.exception.CameraOperationException;
import com.photographer.focusstack.common.camera.CameraCapabilitySnapshot;
import com.photographer.focusstack.common.camera.NativeFocusBracketingProvider;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.photographer.focusstack.common.camera.CameraCapabilitySnapshot.Availability.ADVERTISED_NOT_IMPLEMENTED;
import static com.photographer.focusstack.common.camera.CameraCapabilitySnapshot.Availability.AVAILABLE;
import static com.photographer.focusstack.common.camera.CameraCapabilitySnapshot.Availability.METHOD_METADATA_UNKNOWN;
import static com.photographer.focusstack.common.camera.CameraCapabilitySnapshot.Availability.TEMPORARILY_UNAVAILABLE;
import static com.photographer.focusstack.common.camera.CameraCapabilitySnapshot.Availability.UNSUPPORTED;

/** Builds a host- and credential-free diagnostic view from the exact CCAPI catalog. */
final class CanonCcapiCapabilitySnapshotFactory {

    private static final Pattern VERSION_PATTERN = Pattern.compile("(?:^|/)ver(\\d+)(?:/|$)");

    private static final List<Definition> BASE_DEFINITIONS = List.of(
            definition("device-information", true, methods(HttpMethod.GET), "/deviceinformation"),
            definition("battery-status", true, methods(HttpMethod.GET),
                    "/devicestatus/batterylist", "/devicestatus/battery"),
            definition("storage-status", true, methods(HttpMethod.GET), "/devicestatus/storage"),
            definition("lens-status", true, methods(HttpMethod.GET), "/devicestatus/lens"),
            definition("still-capture", true, methods(HttpMethod.POST),
                    "/shooting/control/shutterbutton/manual", "/shooting/control/shutterbutton"),
            definition("manual-focus-drive", true, methods(HttpMethod.POST),
                    "/shooting/control/drivefocus"),
            definition("live-view-control", true, methods(HttpMethod.POST), "/shooting/liveview"),
            definition("live-view-frame", true, methods(HttpMethod.GET), "/shooting/liveview/flip"),
            definition("media-list-and-download", true, methods(HttpMethod.GET), "/contents"),
            definition("event-polling", true, methods(HttpMethod.GET), "/event/polling"),
            definition("event-monitoring", false, methods(HttpMethod.GET, HttpMethod.DELETE),
                    "/event/monitoring"),
            definition("live-view-multipart", false, methods(HttpMethod.GET, HttpMethod.DELETE),
                    "/shooting/liveview/multipart"),
            definition("live-view-rtp", false, methods(HttpMethod.POST), "/shooting/liveview/rtp")
    );

    private static final List<Definition> NATIVE_BRACKETING_DEFINITIONS = List.of(
            definition("native-focus-bracketing", true, methods(HttpMethod.GET, HttpMethod.PUT),
                    "/shooting/settings/focusbracketing"),
            definition("native-focus-bracketing-shot-count", true,
                    methods(HttpMethod.GET, HttpMethod.PUT),
                    "/shooting/settings/focusbracketing/numberofshots"),
            definition("native-focus-bracketing-increment", true,
                    methods(HttpMethod.GET, HttpMethod.PUT),
                    "/shooting/settings/focusbracketing/focusincrement"),
            definition("native-focus-bracketing-exposure-smoothing", true,
                    methods(HttpMethod.GET, HttpMethod.PUT),
                    "/shooting/settings/focusbracketing/exposuresmoothing"),
            definition("native-focus-bracketing-depth-composition", true,
                    methods(HttpMethod.GET, HttpMethod.PUT),
                    "/shooting/settings/focusbracketing/depthcomposite"),
            definition("native-focus-bracketing-crop-depth-composition", true,
                    methods(HttpMethod.GET, HttpMethod.PUT),
                    "/shooting/settings/focusbracketing/cropdepthcomp")
    );

    /** Publication exports only exact, known operation paths, excluding media names and vendor-private paths. */
    static boolean isDocumentedEvidencePath(String path) {
        Matcher version = VERSION_PATTERN.matcher(path);
        if (!version.find() || !path.substring(0, version.start()).equals("/ccapi")) {
            return false;
        }
        String suffix = "/" + path.substring(version.end());
        return suffix.equals("/shooting/liveview/scroll")
                || java.util.stream.Stream.concat(BASE_DEFINITIONS.stream(), NATIVE_BRACKETING_DEFINITIONS.stream())
                        .anyMatch(definition -> definition.suffixes().contains(suffix));
    }

    private final CanonCcapiStatusBackend backend;
    private final RestTemplate restTemplate;

    CanonCcapiCapabilitySnapshotFactory(
            CanonCcapiStatusBackend backend,
            RestTemplate restTemplate) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.restTemplate = Objects.requireNonNull(restTemplate, "restTemplate");
    }

    CameraCapabilitySnapshot create() {
        List<AdvertisedEndpoint> advertised = advertisedEndpoints();
        Identity identity = readIdentity(advertised);

        List<Definition> definitions = new ArrayList<>(BASE_DEFINITIONS);
        boolean nativeBracketingImplemented = backend instanceof NativeFocusBracketingProvider;
        for (Definition definition : NATIVE_BRACKETING_DEFINITIONS) {
            definitions.add(new Definition(
                    definition.id(),
                    definition.suffixes(),
                    definition.requiredMethods(),
                    nativeBracketingImplemented));
        }

        List<CapabilityResult> resolved = definitions.stream()
                .map(definition -> resolve(definition, advertised))
                .toList();
        LensResult lensResult = readLens(resolved);

        List<CameraCapabilitySnapshot.Capability> capabilities = resolved.stream()
                .map(result -> result.definition().id().equals("lens-status")
                        ? lensResult.capability()
                        : result.capability())
                .toList();
        List<CameraCapabilitySnapshot.Endpoint> endpoints = advertised.stream()
                .map(AdvertisedEndpoint::diagnostic)
                .toList();

        return new CameraCapabilitySnapshot(
                identity.model(),
                identity.firmwareVersion(),
                !advertised.isEmpty(),
                capabilities,
                endpoints,
                lensResult.lens());
    }

    private List<AdvertisedEndpoint> advertisedEndpoints() {
        Map<String, String> urls = backend.getDiscoveredEndpoints();
        Map<String, Set<HttpMethod>> methods = backend.getDiscoveredEndpointMethods();
        List<AdvertisedEndpoint> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : urls.entrySet()) {
            String path = endpointPath(entry.getValue());
            Set<HttpMethod> advertisedMethods = methods.get(entry.getKey());
            boolean metadataKnown = advertisedMethods != null;
            result.add(new AdvertisedEndpoint(
                    version(path),
                    versionNumber(path),
                    path,
                    entry.getValue(),
                    advertisedMethods == null ? Set.of() : advertisedMethods,
                    metadataKnown));
        }
        result.sort(Comparator.comparingInt(AdvertisedEndpoint::versionNumber)
                .thenComparing(AdvertisedEndpoint::path));
        return List.copyOf(result);
    }

    private Identity readIdentity(List<AdvertisedEndpoint> endpoints) {
        AdvertisedEndpoint endpoint = highestExact(endpoints, List.of("/deviceinformation"))
                .filter(candidate -> !candidate.methodMetadataKnown()
                        || candidate.methods().contains(HttpMethod.GET))
                .orElse(null);
        if (endpoint == null) {
            return Identity.UNKNOWN;
        }
        DeviceInformation response = restTemplate.getForObject(endpoint.url(), DeviceInformation.class);
        if (response == null) {
            return Identity.UNKNOWN;
        }
        return new Identity(response.productname(), response.firmwareversion());
    }

    private LensResult readLens(List<CapabilityResult> capabilities) {
        CapabilityResult result = capabilities.stream()
                .filter(candidate -> candidate.definition().id().equals("lens-status"))
                .findFirst()
                .orElseThrow();
        if (result.capability().availability() != AVAILABLE || result.endpoint() == null) {
            return new LensResult(result.capability(), null);
        }

        try {
            LensResponse response = restTemplate.getForObject(
                    result.endpoint().url(), LensResponse.class);
            CameraCapabilitySnapshot.Lens lens = response == null
                    ? new CameraCapabilitySnapshot.Lens(false, null)
                    : new CameraCapabilitySnapshot.Lens(
                            Boolean.TRUE.equals(response.mount()), response.name());
            return new LensResult(result.capability(), lens);
        } catch (RuntimeException exception) {
            Optional<CameraOperationException> operation = operationFailure(exception);
            if (operation.isPresent()
                    && operation.orElseThrow().category()
                    == CameraOperationException.Category.TEMPORARY_STATE) {
                CameraOperationException failure = operation.orElseThrow();
                CameraCapabilitySnapshot.Capability temporary = new CameraCapabilitySnapshot.Capability(
                        result.capability().id(),
                        true,
                        true,
                        TEMPORARILY_UNAVAILABLE,
                        result.capability().requiredMethods(),
                        result.capability().advertisedMethods(),
                        result.capability().methodMetadataKnown(),
                        failure.reason().name());
                return new LensResult(temporary, null);
            }
            throw exception;
        }
    }

    private static CapabilityResult resolve(
            Definition definition,
            List<AdvertisedEndpoint> endpoints) {
        AdvertisedEndpoint endpoint = CcapiEndpointCatalog.isLiveViewOperation(definition.suffixes().getFirst())
                ? highestCompatibleLiveView(endpoints, definition)
                : highestExact(endpoints, definition.suffixes()).orElse(null);
        if (endpoint == null) {
            return new CapabilityResult(definition, new CameraCapabilitySnapshot.Capability(
                    definition.id(),
                    false,
                    definition.implemented(),
                    UNSUPPORTED,
                    methodNames(definition.requiredMethods()),
                    Set.of(),
                    false,
                    "ENDPOINT_NOT_ADVERTISED"), null);
        }
        if (!definition.implemented()) {
            return new CapabilityResult(definition, new CameraCapabilitySnapshot.Capability(
                    definition.id(),
                    true,
                    false,
                    ADVERTISED_NOT_IMPLEMENTED,
                    methodNames(definition.requiredMethods()),
                    methodNames(endpoint.methods()),
                    endpoint.methodMetadataKnown(),
                    "APPLICATION_NOT_IMPLEMENTED"), endpoint);
        }
        if (!endpoint.methodMetadataKnown()) {
            return new CapabilityResult(definition, new CameraCapabilitySnapshot.Capability(
                    definition.id(),
                    true,
                    true,
                    METHOD_METADATA_UNKNOWN,
                    methodNames(definition.requiredMethods()),
                    Set.of(),
                    false,
                    "METHOD_METADATA_NOT_ADVERTISED"), endpoint);
        }
        if (!endpoint.methods().containsAll(definition.requiredMethods())) {
            return new CapabilityResult(definition, new CameraCapabilitySnapshot.Capability(
                    definition.id(),
                    true,
                    true,
                    UNSUPPORTED,
                    methodNames(definition.requiredMethods()),
                    methodNames(endpoint.methods()),
                    true,
                    "METHOD_NOT_ADVERTISED"), endpoint);
        }
        return new CapabilityResult(definition, new CameraCapabilitySnapshot.Capability(
                definition.id(),
                true,
                true,
                AVAILABLE,
                methodNames(definition.requiredMethods()),
                methodNames(endpoint.methods()),
                true,
                null), endpoint);
    }

    private static AdvertisedEndpoint highestCompatibleLiveView(
            List<AdvertisedEndpoint> endpoints, Definition definition) {
        String operation = definition.suffixes().getFirst();
        Comparator<AdvertisedEndpoint> order = Comparator
                .comparing((AdvertisedEndpoint endpoint) -> !endpoint.methodMetadataKnown()
                        || endpoint.methods().containsAll(definition.requiredMethods()))
                .thenComparingInt(AdvertisedEndpoint::versionNumber)
                .thenComparing(AdvertisedEndpoint::path);
        return endpoints.stream()
                .filter(endpoint -> CcapiEndpointCatalog.matchesOperation(endpoint.path(), operation))
                .max(order)
                .orElse(null);
    }

    private static Optional<AdvertisedEndpoint> highestExact(
            List<AdvertisedEndpoint> endpoints,
            List<String> suffixes) {
        return endpoints.stream()
                .filter(endpoint -> suffixes.stream().anyMatch(endpoint.path()::endsWith))
                .max(Comparator.comparingInt(AdvertisedEndpoint::versionNumber)
                        .thenComparing(AdvertisedEndpoint::path));
    }

    private static Optional<CameraOperationException> operationFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof CameraOperationException operation) {
                return Optional.of(operation);
            }
            current = current.getCause();
        }
        return Optional.empty();
    }

    private static String endpointPath(String value) {
        try {
            String path = URI.create(value).getPath();
            return path == null || path.isBlank() ? "/" : path;
        } catch (IllegalArgumentException exception) {
            int query = value.indexOf('?');
            return query >= 0 ? value.substring(0, query) : value;
        }
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

    private static Set<HttpMethod> methods(HttpMethod... methods) {
        return Set.of(methods);
    }

    private static Set<String> methodNames(Set<HttpMethod> methods) {
        return methods.stream()
                .map(HttpMethod::name)
                .sorted()
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private static Definition definition(
            String id,
            boolean implemented,
            Set<HttpMethod> methods,
            String... suffixes) {
        return new Definition(id, List.of(suffixes), methods, implemented);
    }

    private record Definition(
            String id,
            List<String> suffixes,
            Set<HttpMethod> requiredMethods,
            boolean implemented) {
    }

    private record CapabilityResult(
            Definition definition,
            CameraCapabilitySnapshot.Capability capability,
            AdvertisedEndpoint endpoint) {
    }

    private record AdvertisedEndpoint(
            String version,
            int versionNumber,
            String path,
            String url,
            Set<HttpMethod> methods,
            boolean methodMetadataKnown) {

        private CameraCapabilitySnapshot.Endpoint diagnostic() {
            return new CameraCapabilitySnapshot.Endpoint(
                    version, path, methodNames(methods), methodMetadataKnown);
        }
    }

    private record LensResult(
            CameraCapabilitySnapshot.Capability capability,
            CameraCapabilitySnapshot.Lens lens) {
    }

    private record Identity(String model, String firmwareVersion) {
        private static final Identity UNKNOWN = new Identity("unknown", "unknown");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DeviceInformation(
            @JsonProperty("productname") String productname,
            @JsonProperty("firmwareversion") String firmwareversion) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LensResponse(
            @JsonProperty("mount") Boolean mount,
            @JsonProperty("name") String name) {
    }
}
