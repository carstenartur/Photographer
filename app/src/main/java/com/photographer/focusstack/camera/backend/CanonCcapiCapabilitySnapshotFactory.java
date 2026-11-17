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
import java.util.LinkedHashSet;
import java.util.List;
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
            new Definition("lens-status", List.of("/devicestatus/lens"), methods(HttpMethod.GET), true, false),
            definition("still-capture", true, methods(HttpMethod.POST),
                    "/shooting/control/shutterbutton/manual", "/shooting/control/shutterbutton"),
            definition("manual-focus-drive", true, methods(HttpMethod.POST),
                    "/shooting/control/drivefocus"),
            definition("live-view-control", true, methods(HttpMethod.POST), "/shooting/liveview"),
            definition("live-view-frame", true, methods(HttpMethod.GET), "/shooting/liveview/flip"),
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
        for (CcapiMediaOperation operation : CcapiMediaOperation.values()) {
            if (suffix.equals(operation.path)) {
                return true;
            }
        }
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

    CameraCapabilitySnapshot create(boolean extendedEvents) {
        CcapiEndpointCatalog catalog = backend.endpointCatalog();
        Identity identity = readIdentity(catalog);

        List<Definition> definitions = new ArrayList<>(BASE_DEFINITIONS);
        for (CcapiMediaOperation operation : CcapiMediaOperation.values()) {
            if (operation != CcapiMediaOperation.POLLING_CONTINUATION || extendedEvents) {
                definitions.add(new Definition(operation.capability, List.of(operation.path), operation.methods,
                        operation != CcapiMediaOperation.MONITORING || extendedEvents, operation.allowUnknownMethods));
            }
        }
        boolean nativeBracketingImplemented = backend instanceof NativeFocusBracketingProvider;
        for (Definition definition : NATIVE_BRACKETING_DEFINITIONS) {
            definitions.add(new Definition(
                    definition.id(),
                    definition.suffixes(),
                    definition.requiredMethods(),
                    nativeBracketingImplemented, false));
        }

        List<CapabilityResult> resolved = definitions.stream()
                .map(definition -> resolve(definition, catalog))
                .toList();
        LensResult lensResult = readLens(resolved);

        List<CameraCapabilitySnapshot.Capability> capabilities = resolved.stream()
                .map(result -> result.definition().id().equals("lens-status")
                        ? lensResult.capability()
                        : result.capability())
                .toList();
        List<CameraCapabilitySnapshot.Endpoint> endpoints = catalog.endpoints().stream()
                .map(endpoint -> new CameraCapabilitySnapshot.Endpoint(endpoint.version(), endpointPath(endpoint.url()),
                        methodNames(endpoint.methods()), endpoint.methodMetadataKnown()))
                .toList();

        return new CameraCapabilitySnapshot(
                identity.model(),
                identity.firmwareVersion(),
                !endpoints.isEmpty(),
                capabilities,
                endpoints,
                lensResult.lens());
    }

    private Identity readIdentity(CcapiEndpointCatalog catalog) {
        CcapiEndpointCatalog.Endpoint endpoint = catalog.findOperation("/deviceinformation", methods(HttpMethod.GET), true)
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
            CcapiEndpointCatalog catalog) {
        CcapiEndpointCatalog.Endpoint endpoint = bestEndpoint(catalog, definition.suffixes(),
                definition.requiredMethods(), definition.allowUnknownMethods())
                .or(() -> bestEndpoint(catalog, definition.suffixes(), Set.of(), true))
                .orElse(null);
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

    private static Optional<CcapiEndpointCatalog.Endpoint> bestEndpoint(
            CcapiEndpointCatalog catalog, List<String> operations, Set<HttpMethod> methods, boolean allowUnknownMethods) {
        return operations.stream()
                .map(operation -> catalog.findOperation(operation, methods, allowUnknownMethods))
                .flatMap(Optional::stream)
                .max(CcapiEndpointCatalog.VERSION_ORDER);
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
            String path = URI.create(value).getRawPath();
            return path == null || path.isBlank() ? "/" : path;
        } catch (IllegalArgumentException exception) {
            int query = value.indexOf('?');
            return query >= 0 ? value.substring(0, query) : value;
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
        return new Definition(id, List.of(suffixes), methods, implemented, true);
    }

    private record Definition(
            String id,
            List<String> suffixes,
            Set<HttpMethod> requiredMethods,
            boolean implemented,
            boolean allowUnknownMethods) {
    }

    private record CapabilityResult(
            Definition definition,
            CameraCapabilitySnapshot.Capability capability,
            CcapiEndpointCatalog.Endpoint endpoint) {
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
