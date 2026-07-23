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
import com.photographer.focusstack.common.camera.NativeFocusBracketingExecutionProvider;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static com.photographer.focusstack.common.camera.CameraCapabilitySnapshot.Availability.AVAILABLE;
import static com.photographer.focusstack.common.camera.CameraCapabilitySnapshot.Availability.METHOD_METADATA_UNKNOWN;
import static com.photographer.focusstack.common.camera.CameraCapabilitySnapshot.Availability.UNSUPPORTED;

/** Production Canon CCAPI composition with one authoritative endpoint catalog. */
final class CanonCcapiProductionBackend implements
        CameraBackend,
        CameraStatusProvider,
        CameraCapabilityProvider,
        NativeFocusBracketingExecutionProvider,
        CameraMediaProvider {

    private static final Set<String> EVENT_METHODS = Set.of("GET", "DELETE");

    private final CanonCcapiNativeFocusBracketingBackend delegate;
    private final CcapiLiveViewClient liveViewClient;
    private final CanonCcapiMediaClient mediaClient;
    private final CanonCcapiMonitoringClient monitoringClient;

    CanonCcapiProductionBackend(
            String profileId,
            String baseUrl,
            RestTemplate restTemplate) {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(restTemplate, "restTemplate");
        this.delegate = new CanonCcapiNativeFocusBracketingBackend(baseUrl, restTemplate);
        this.liveViewClient = new CcapiLiveViewClient(
                profileId,
                restTemplate,
                delegate::getDiscoveredEndpoints,
                delegate::getDiscoveredEndpointMethods,
                delegate::ccapiPost);
        this.mediaClient = new CanonCcapiMediaClient(
                profileId,
                baseUrl,
                restTemplate,
                delegate::getDiscoveredEndpoints,
                delegate::getDiscoveredEndpointMethods);
        this.monitoringClient = new CanonCcapiMonitoringClient(
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
        return liveViewClient.getFrame();
    }

    @Override
    public void stopLiveView() {
        liveViewClient.stop();
    }

    @Override
    public CaptureResult capture() {
        // The inherited capture sequence starts by stopping live view through the
        // delegate. Reset the production lifecycle first so a later frame request
        // performs one capability-driven restart rather than trusting stale state.
        liveViewClient.markStopped();
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
        return operation == Operation.LIVE_VIEW
                ? liveViewClient.supportsFrameRetrieval()
                : delegate.supports(operation);
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
        if (capabilities.stream().noneMatch(
                capability -> "live-view-scroll".equals(capability.id()))) {
            capabilities.add(CcapiLiveViewCapabilities.scroll(base));
        }

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
        List<CameraCapabilitySnapshot.Endpoint> advertised = snapshot.advertisedEndpoints().stream()
                .filter(candidate -> candidate.path().endsWith(suffix))
                .toList();
        if (advertised.isEmpty()) {
            return new CameraCapabilitySnapshot.Capability(
                    id, false, true, UNSUPPORTED,
                    EVENT_METHODS, Set.of(), false, "ENDPOINT_NOT_ADVERTISED");
        }

        CameraCapabilitySnapshot.Endpoint compatible = advertised.stream()
                .filter(CameraCapabilitySnapshot.Endpoint::methodMetadataKnown)
                .filter(candidate -> candidate.methods().containsAll(EVENT_METHODS))
                .max(Comparator.comparingInt(CanonCcapiProductionBackend::versionNumber)
                        .thenComparing(CameraCapabilitySnapshot.Endpoint::path))
                .orElse(null);
        if (compatible != null) {
            return new CameraCapabilitySnapshot.Capability(
                    id, true, true, AVAILABLE,
                    EVENT_METHODS, compatible.methods(), true, null);
        }

        CameraCapabilitySnapshot.Endpoint highest = advertised.stream()
                .max(Comparator.comparingInt(CanonCcapiProductionBackend::versionNumber)
                        .thenComparing(CameraCapabilitySnapshot.Endpoint::path))
                .orElseThrow();
        if (!highest.methodMetadataKnown()) {
            return new CameraCapabilitySnapshot.Capability(
                    id, true, true, METHOD_METADATA_UNKNOWN,
                    EVENT_METHODS, Set.of(), false, "METHOD_METADATA_NOT_ADVERTISED");
        }
        return new CameraCapabilitySnapshot.Capability(
                id, true, true, UNSUPPORTED,
                EVENT_METHODS, highest.methods(), true, "METHOD_NOT_ADVERTISED");
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
        return new OwnedNativeSession(
                this, delegate.prepareNativeFocusBracketing(request));
    }

    @Override
    public void triggerNativeFocusBracketing(NativeFocusBracketingSession session) {
        if (!(session instanceof OwnedNativeSession owned) || owned.owner != this) {
            throw new IllegalArgumentException(
                    "native focus-bracketing session belongs to another backend");
        }
        owned.trigger(() -> CanonCcapiNativeSequenceTrigger.trigger(delegate));
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
        return mode == EventMode.MONITORING
                ? monitoringClient.awaitAddedMedia(timeout)
                : mediaClient.awaitAddedMedia(mode, timeout);
    }

    private static final class OwnedNativeSession implements NativeFocusBracketingSession {
        private final CanonCcapiProductionBackend owner;
        private final NativeFocusBracketingSession delegate;
        private final AtomicBoolean triggered = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();

        private OwnedNativeSession(
                CanonCcapiProductionBackend owner,
                NativeFocusBracketingSession delegate) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public NativeFocusBracketingRequest request() {
            return delegate.request();
        }

        private void trigger(Runnable triggerAction) {
            if (closed.get()) {
                throw new IllegalStateException(
                        "native focus-bracketing session is already closed");
            }
            if (!triggered.compareAndSet(false, true)) {
                throw new IllegalStateException(
                        "native focus-bracketing session was already triggered");
            }
            triggerAction.run();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                delegate.close();
            } catch (RuntimeException failure) {
                closed.set(false);
                throw failure;
            }
        }
    }
}
