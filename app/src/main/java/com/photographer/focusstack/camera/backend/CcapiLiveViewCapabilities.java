package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.common.camera.CameraCapabilitySnapshot;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static com.photographer.focusstack.common.camera.CameraCapabilitySnapshot.Availability.ADVERTISED_NOT_IMPLEMENTED;
import static com.photographer.focusstack.common.camera.CameraCapabilitySnapshot.Availability.UNSUPPORTED;

/** Adds vendor-neutral live-view diagnostics not yet represented by the base snapshot. */
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
                    false,
                    UNSUPPORTED,
                    SCROLL_METHODS,
                    Set.of(),
                    false,
                    "ENDPOINT_NOT_ADVERTISED");
        }

        CameraCapabilitySnapshot.Endpoint highest = advertised.stream()
                .max(Comparator.comparingInt(CcapiLiveViewCapabilities::versionNumber)
                        .thenComparing(CameraCapabilitySnapshot.Endpoint::path))
                .orElseThrow();
        return new CameraCapabilitySnapshot.Capability(
                "live-view-scroll",
                true,
                false,
                ADVERTISED_NOT_IMPLEMENTED,
                SCROLL_METHODS,
                highest.methods(),
                highest.methodMetadataKnown(),
                "APPLICATION_NOT_IMPLEMENTED");
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
