package com.photographer.focusstack.common.capability;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Sanitized capability evidence for one connected camera.
 *
 * <p>Endpoint evidence contains only version, normalized path and methods. It
 * deliberately excludes host names, absolute URLs, serial numbers and credentials.</p>
 */
public record CapabilityStatusSnapshot(
        String model,
        String firmwareVersion,
        Instant observedAt,
        Map<Capability, CapabilityStatus> capabilities,
        List<AdvertisedEndpoint> advertisedEndpoints) {

    public CapabilityStatusSnapshot {
        model = normalizedOrUnknown(model);
        firmwareVersion = normalizedOrUnknown(firmwareVersion);
        observedAt = Objects.requireNonNull(observedAt, "observedAt");

        EnumMap<Capability, CapabilityStatus> complete = new EnumMap<>(Capability.class);
        for (Capability capability : Capability.values()) {
            complete.put(capability, CapabilityStatus.unknown(capability));
        }
        if (capabilities != null) {
            capabilities.forEach((capability, status) -> {
                Objects.requireNonNull(capability, "capability key");
                Objects.requireNonNull(status, "capability status");
                if (status.capability() != capability) {
                    throw new IllegalArgumentException(
                            "Capability map key does not match status capability");
                }
                complete.put(capability, status);
            });
        }
        capabilities = Map.copyOf(complete);
        advertisedEndpoints = advertisedEndpoints == null
                ? List.of()
                : List.copyOf(new ArrayList<>(advertisedEndpoints));
    }

    public static CapabilityStatusSnapshot unknown(String model, String firmwareVersion) {
        return new CapabilityStatusSnapshot(
                model, firmwareVersion, Instant.now(), Map.of(), List.of());
    }

    public CapabilityStatus status(Capability capability) {
        return capabilities.get(Objects.requireNonNull(capability, "capability"));
    }

    public CapabilitySet availableCapabilities() {
        CapabilitySet.Builder builder = CapabilitySet.builder();
        capabilities.values().stream()
                .filter(status -> status.state() == CapabilityStatus.State.AVAILABLE)
                .forEach(status -> {
                    if (status.access() == CapabilitySet.Access.READ_WRITE) {
                        builder.addRw(status.capability());
                    } else {
                        builder.add(status.capability());
                    }
                });
        return builder.build();
    }

    private static String normalizedOrUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }

    /** One sanitized endpoint advertisement retained for diagnostics. */
    public record AdvertisedEndpoint(
            String version,
            String path,
            Set<String> methods,
            boolean methodMetadataKnown) {

        public AdvertisedEndpoint {
            version = Objects.requireNonNull(version, "version");
            path = Objects.requireNonNull(path, "path");
            if (!path.startsWith("/")) {
                throw new IllegalArgumentException("Advertised endpoint path must be absolute");
            }
            methods = methods == null ? Set.of() : Set.copyOf(methods);
        }
    }
}
