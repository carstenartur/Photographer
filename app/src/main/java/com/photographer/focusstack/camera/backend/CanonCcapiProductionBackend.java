package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.camera.CameraCapabilityProvider;
import com.photographer.focusstack.common.camera.CameraCapabilitySnapshot;
import com.photographer.focusstack.common.camera.CameraInfo;
import com.photographer.focusstack.common.camera.CameraMediaProvider;
import com.photographer.focusstack.common.camera.CameraMediaReference;
import com.photographer.focusstack.common.camera.CameraState;
import com.photographer.focusstack.common.camera.CameraStatusProvider;
import com.photographer.focusstack.common.camera.CaptureResult;
import com.photographer.focusstack.common.camera.ExposureInfo;
import com.photographer.focusstack.common.camera.FocusCommand;
import com.photographer.focusstack.common.camera.LiveViewFrame;
import com.photographer.focusstack.common.camera.NativeFocusBracketingCapabilities;
import com.photographer.focusstack.common.camera.NativeFocusBracketingProvider;
import com.photographer.focusstack.common.camera.NativeFocusBracketingRequest;
import com.photographer.focusstack.common.camera.NativeFocusBracketingSession;
import com.photographer.focusstack.common.camera.Operation;
import com.photographer.focusstack.common.device.BatteryInfo;
import com.photographer.focusstack.common.device.StorageInfo;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static com.photographer.focusstack.common.camera.CameraCapabilitySnapshot.Availability.AVAILABLE;
import static com.photographer.focusstack.common.camera.CameraCapabilitySnapshot.Availability.METHOD_METADATA_UNKNOWN;
import static com.photographer.focusstack.common.camera.CameraCapabilitySnapshot.Availability.UNSUPPORTED;

/**
 * Production Canon CCAPI composition with one authoritative endpoint catalog.
 *
 * <p>The established status/native backend remains responsible for discovery and
 * camera control. The bounded media client consumes that backend's normalized
 * endpoint and method views rather than performing a second discovery.</p>
 */
final class CanonCcapiProductionBackend implements
        CameraBackend,
        CameraStatusProvider,
        CameraCapabilityProvider,
        NativeFocusBracketingProvider,
        CameraMediaProvider {

    private static final Set<String> EVENT_METHODS = Set.of("GET", "DELETE");

    private final CanonCcapiNativeFocusBracketingBackend delegate;
    private final CanonCcapiMediaClient mediaClient;

    CanonCcapiProductionBackend(
            String profileId,
            String baseUrl,
            RestTemplate restTemplate) {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(restTemplate, "restTemplate");
        this.delegate = new CanonCcapiNativeFocusBracketingBackend(baseUrl, restTemplate);
        this.mediaClient = new CanonCcapiMediaClient(
                profileId,
                baseUrl,
                restTemplate,
                delegate::getDiscoveredEndpoints,
                delegate::getDiscoveredEndpointMethods);
    }

    @Override
    public CameraInfo getInfo() {
        return delegate.getInfo();
    }

    @Override
    public LiveViewFrame getLiveView() {
        return delegate.getLiveView();
    }

    @Override
    public void stopLiveView() {
        delegate.stopLiveView();
    }

    @Override
    public CaptureResult capture() {
        return delegate.capture();
    }

    @Override
    public void setFocus(FocusCommand command) {
        delegate.setFocus(command);
    }

    @Override
    public CameraState getState() {
        return delegate.getState();
    }

    @Override
    public boolean supports(Operation operation) {
        return delegate.supports(operation);
    }

    @Override
    public ExposureInfo getExposureInfo() {
        return delegate.getExposureInfo();
    }

    @Override
    public void setIso(int rawValue) {
        delegate.setIso(rawValue);
    }

    @Override
    public void setShutterSpeed(int rawValue) {
        delegate.setShutterSpeed(rawValue);
    }

    @Override
    public void setAperture(int rawValue) {
        delegate.setAperture(rawValue);
    }

    @Override
    public BatteryInfo getBatteryInfo() {
        return delegate.getBatteryInfo();
    }

    @Override
    public StorageInfo getStorageInfo() {
        return delegate.getStorageInfo();
    }

    @Override
    public CameraCapabilitySnapshot getCapabilitySnapshot() {
        CameraCapabilitySnapshot base = delegate.getCapabilitySnapshot();
        List<CameraCapabilitySnapshot.Capability> capabilities = new ArrayList<>();
        boolean monitoringReplaced = false;
        for (CameraCapabilitySnapshot.Capability capability : base.capabilities()) {
            if ("event-monitoring".equals(capability.id())) {
                capabilities.add(eventCapability(base, "event-monitoring", "/event/monitoring"));
                monitoringReplaced = true;
            } else {
                capabilities.add(capability);
            }
        }
        if (!monitoringReplaced) {
            capabilities.add(eventCapability(base, "event-monitoring", "/event/monitoring"));
        }
        capabilities.add(eventCapability(
                base, "event-polling-continuation", "/event/polling"));

        return new CameraCapabilitySnapshot(
                base.model(),
                base.firmwareVersion(),
                base.catalogAvailable(),
                capabilities,
                base.advertisedEndpoints(),
                base.lens());
    }

    private static CameraCapabilitySnapshot.Capability eventCapability(
            CameraCapabilitySnapshot snapshot,
            String id,
            String suffix) {
        CameraCapabilitySnapshot.Endpoint endpoint = snapshot.advertisedEndpoints().stream()
                .filter(candidate -> candidate.path().endsWith(suffix))
                .max(Comparator.comparingInt(CanonCcapiProductionBackend::versionNumber)
                        .thenComparing(CameraCapabilitySnapshot.Endpoint::path))
                .orElse(null);
        if (endpoint == null) {
            return new CameraCapabilitySnapshot.Capability(
                    id, false, true, UNSUPPORTED,
                    EVENT_METHODS, Set.of(), false, "ENDPOINT_NOT_ADVERTISED");
        }
        if (!endpoint.methodMetadataKnown()) {
            return new CameraCapabilitySnapshot.Capability(
                    id, true, true, METHOD_METADATA_UNKNOWN,
                    EVENT_METHODS, Set.of(), false, "METHOD_METADATA_NOT_ADVERTISED");
        }
        if (!endpoint.methods().containsAll(EVENT_METHODS)) {
            return new CameraCapabilitySnapshot.Capability(
                    id, true, true, UNSUPPORTED,
                    EVENT_METHODS, endpoint.methods(), true, "METHOD_NOT_ADVERTISED");
        }
        return new CameraCapabilitySnapshot.Capability(
                id, true, true, AVAILABLE,
                EVENT_METHODS, endpoint.methods(), true, null);
    }

    private static int versionNumber(CameraCapabilitySnapshot.Endpoint endpoint) {
        String version = endpoint.version();
        if (version == null || !version.startsWith("ver")) {
            return -1;
        }
        try {
            return Integer.parseInt(version.substring(3));
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    @Override
    public NativeFocusBracketingCapabilities getNativeFocusBracketingCapabilities() {
        return delegate.getNativeFocusBracketingCapabilities();
    }

    @Override
    public NativeFocusBracketingSession prepareNativeFocusBracketing(
            NativeFocusBracketingRequest request) {
        return delegate.prepareNativeFocusBracketing(request);
    }

    @Override
    public MediaPage listMedia(MediaQuery query) {
        return mediaClient.listMedia(query);
    }

    @Override
    public MediaChunk downloadMedia(
            CameraMediaReference reference,
            long offset,
            int maxBytes) {
        return mediaClient.downloadMedia(reference, offset, maxBytes);
    }

    @Override
    public Optional<CameraMediaReference> awaitAddedMedia(
            EventMode mode,
            Duration timeout) {
        return mediaClient.awaitAddedMedia(mode, timeout);
    }
}
