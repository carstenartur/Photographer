package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.common.camera.CameraCapabilitySnapshot;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static com.photographer.focusstack.common.camera.CameraCapabilitySnapshot.Availability.AVAILABLE;
import static com.photographer.focusstack.common.camera.CameraCapabilitySnapshot.Availability.METHOD_METADATA_UNKNOWN;
import static com.photographer.focusstack.common.camera.CameraCapabilitySnapshot.Availability.UNSUPPORTED;

/** Adds vendor-neutral live-view diagnostics not represented by the base snapshot. */
final class CcapiLiveViewCapabilities {

    private static final String SCROLL_SUFFIX = "/shooting/liveview/scroll";
    private static final Set<String> SCROLL_METHODS = Set.of("GET", "DELETE");

    private CcapiLiveViewCapabilities() {
    }

    static CameraCapabilitySnapshot.Capability scroll(
            CameraCapabilitySnapshot snapshot) {
        List<CameraCapabilitySnapshot.Endpoint> advertised = snapshot.advertisedEndpoints().stream()
                .filter(endpoint -> endpoint.path().endsWith(SCROLL_SUFFIX))
                .toList();
        if (advertised.isEmpty()) {
            return new CameraCapabilitySnapshot.Capability(
                    "live-view-scroll",
                    false,
                    true,
                    UNSUPPORTED,
                    SCROLL_METHODS,
                    Set.of(),
                    false,
                    "ENDPOINT_NOT_ADVERTISED");
        }

        CameraCapabilitySnapshot.Endpoint compatible = advertised.stream()
                .filter(CameraCapabilitySnapshot.Endpoint::methodMetadataKnown)
                .filter(endpoint -> endpoint.methods().containsAll(SCROLL_METHODS))
                .max(Comparator.comparingInt(CcapiLiveViewCapabilities::versionNumber)
                        .thenComparing(CameraCapabilitySnapshot.Endpoint::path))
                .orElse(null);
        if (compatible != null) {
            return new CameraCapabilitySnapshot.Capability(
                    "live-view-scroll",
                    true,
                    true,
                    AVAILABLE,
                    SCROLL_METHODS,
                    compatible.methods(),
                    true,
                    null);
        }

        CameraCapabilitySnapshot.Endpoint highest = advertised.stream()
                .max(Comparator.comparingInt(CcapiLiveViewCapabilities::versionNumber)
                        .thenComparing(CameraCapabilitySnapshot.Endpoint::path))
                .orElseThrow();
        if (!highest.methodMetadataKnown()) {
            return new CameraCapabilitySnapshot.Capability(
                    "live-view-scroll",
                    true,
                    true,
                    METHOD_METADATA_UNKNOWN,
                    SCROLL_METHODS,
                    Set.of(),
                    false,
                    "METHOD_METADATA_NOT_ADVERTISED");
        }
        return new CameraCapabilitySnapshot.Capability(
                "live-view-scroll",
                true,
                true,
                UNSUPPORTED,
                SCROLL_METHODS,
                highest.methods(),
                true,
                "METHOD_NOT_ADVERTISED");
    }

    private static int versionNumber(CameraCapabilitySnapshot.Endpoint endpoint) {
        String version = endpoint.version();
        if (version == null || !version.startsWith("ver")) {
            return -1;
        }
        try {
            return Integer.parseInt(version.substring(3));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}
