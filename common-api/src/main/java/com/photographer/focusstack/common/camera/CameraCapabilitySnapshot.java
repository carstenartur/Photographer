package com.photographer.focusstack.common.camera;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Redacted, deterministic capability snapshot for one camera backend.
 *
 * <p>Advertised support and application implementation are deliberately separate.
 * An endpoint may be advertised by the camera but not implemented by the application,
 * or implemented by the application but unavailable on a particular camera. Absolute
 * transport URLs, hosts, serial numbers and authentication data are not part of this
 * contract.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CameraCapabilitySnapshot(
        String model,
        String firmwareVersion,
        boolean catalogAvailable,
        List<Capability> capabilities,
        List<Endpoint> advertisedEndpoints,
        Lens lens
) {

    public CameraCapabilitySnapshot {
        model = normalizedOrUnknown(model);
        firmwareVersion = normalizedOrUnknown(firmwareVersion);
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        advertisedEndpoints = advertisedEndpoints == null
                ? List.of() : List.copyOf(advertisedEndpoints);
    }

    /** Creates a safe response for a backend that does not expose capability diagnostics. */
    public static CameraCapabilitySnapshot diagnosticsUnavailable(CameraInfo info) {
        Objects.requireNonNull(info, "info");
        return new CameraCapabilitySnapshot(
                info.model(),
                info.firmwareVersion(),
                false,
                List.of(),
                List.of(),
                null);
    }

    /** User-facing availability of one semantic camera capability. */
    public enum Availability {
        AVAILABLE,
        UNSUPPORTED,
        TEMPORARILY_UNAVAILABLE,
        ADVERTISED_NOT_IMPLEMENTED,
        METHOD_METADATA_UNKNOWN
    }

    /**
     * One semantic, vendor-neutral capability.
     *
     * @param id stable capability identifier
     * @param advertised whether an exact camera endpoint was advertised
     * @param implemented whether the application implements the semantic operation
     * @param availability current availability classification
     * @param requiredMethods methods required by the application implementation
     * @param advertisedMethods methods advertised by the selected endpoint
     * @param methodMetadataKnown whether the camera supplied method flags
     * @param condition optional stable condition code, never a downstream message
     */
    public record Capability(
            String id,
            boolean advertised,
            boolean implemented,
            Availability availability,
            Set<String> requiredMethods,
            Set<String> advertisedMethods,
            boolean methodMetadataKnown,
            String condition
    ) {
        public Capability {
            id = requireToken(id, "id");
            availability = Objects.requireNonNull(availability, "availability");
            requiredMethods = normalizedMethods(requiredMethods);
            advertisedMethods = normalizedMethods(advertisedMethods);
            condition = condition == null || condition.isBlank()
                    ? null : condition.trim().toUpperCase(Locale.ROOT);
        }
    }

    /** One redacted advertised endpoint. Only version, path and method flags are retained. */
    public record Endpoint(
            String version,
            String path,
            Set<String> methods,
            boolean methodMetadataKnown
    ) {
        public Endpoint {
            version = requireToken(version, "version");
            path = Objects.requireNonNull(path, "path").trim();
            if (!path.startsWith("/") || path.contains("://")) {
                throw new IllegalArgumentException(
                        "Endpoint diagnostic must contain a relative absolute-path only");
            }
            methods = normalizedMethods(methods);
        }
    }

    /** Optional lens status, present only when the camera advertises the lens endpoint. */
    public record Lens(boolean mounted, String name) {
        public Lens {
            name = name == null || name.isBlank() ? null : name.trim();
        }
    }

    private static String normalizedOrUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }

    private static String requireToken(String value, String field) {
        Objects.requireNonNull(value, field);
        String result = value.trim();
        if (result.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return result;
    }

    private static Set<String> normalizedMethods(Set<String> methods) {
        if (methods == null || methods.isEmpty()) {
            return Set.of();
        }
        TreeSet<String> ordered = new TreeSet<>();
        for (String method : methods) {
            if (method != null && !method.isBlank()) {
                ordered.add(method.trim().toUpperCase(Locale.ROOT));
            }
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(ordered));
    }
}
