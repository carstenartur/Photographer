package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.camera.exception.CameraOperationException;
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
import com.photographer.focusstack.common.camera.NativeFocusBracketingTransportException;
import com.photographer.focusstack.common.camera.Operation;
import com.photographer.focusstack.common.camera.ProcessingCaptureProvider;
import com.photographer.focusstack.common.device.BatteryInfo;
import com.photographer.focusstack.common.device.StorageInfo;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.LIVE_VIEW_NOT_STARTED;

/** Production Canon CCAPI composition with one authoritative endpoint catalog. */
final class CanonCcapiProductionBackend implements
        CameraBackend,
        ProcessingCaptureProvider,
        CameraStatusProvider,
        CameraCapabilityProvider,
        NativeFocusBracketingExecutionProvider,
        CameraMediaProvider {

    private static final Set<HttpMethod> SCROLL_METHODS = Set.of(HttpMethod.GET, HttpMethod.DELETE);
    private static final String SCROLL_SUFFIX = "/shooting/liveview/scroll";
    private static final Pattern VERSION = Pattern.compile("(?:^|/)ver(\\d+)(?:/|$)");

    private final CanonCcapiNativeFocusBracketingBackend delegate;
    private final CcapiLiveViewClient liveViewClient;
    private final CanonCcapiScrollLiveViewReader scrollLiveViewReader;
    private final CanonCcapiMediaClient mediaClient;
    private final CanonCcapiMonitoringClient monitoringClient;

    CanonCcapiProductionBackend(String profileId, String baseUrl, RestTemplate restTemplate) {
        this(profileId, baseUrl, restTemplate,
                new CanonCcapiScrollLiveViewReader(profileId, restTemplate));
    }

    CanonCcapiProductionBackend(
            String profileId,
            String baseUrl,
            RestTemplate restTemplate,
            CanonCcapiScrollLiveViewReader scrollLiveViewReader) {
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
        this.scrollLiveViewReader = Objects.requireNonNull(
                scrollLiveViewReader, "scrollLiveViewReader");
        this.mediaClient = new CanonCcapiMediaClient(
                profileId,
                baseUrl,
                restTemplate,
                delegate::endpointCatalog);
        this.monitoringClient = new CanonCcapiMonitoringClient(
                profileId,
                baseUrl,
                restTemplate,
                delegate::endpointCatalog);
    }

    @Override
    public CameraInfo getInfo() {
        return delegate.getInfo();
    }

    @Override
    public LiveViewFrame getLiveView() {
        if (liveViewClient.supportsFrameRetrieval()) {
            return liveViewClient.getFrame();
        }
        String scrollUrl = scrollEndpoint().orElse(null);
        if (scrollUrl == null) {
            return liveViewClient.getFrame();
        }

        liveViewClient.start("medium");
        try {
            return scrollLiveViewReader.nextFrame(scrollUrl);
        } catch (RuntimeException failure) {
            if (reason(failure) != LIVE_VIEW_NOT_STARTED) {
                throw failure;
            }
            scrollLiveViewReader.abort();
            liveViewClient.markStopped();
            liveViewClient.start("medium");
            return scrollLiveViewReader.nextFrame(scrollUrl);
        }
    }

    @Override
    public void stopLiveView() {
        try {
            scrollLiveViewReader.stop();
        } finally {
            liveViewClient.stop();
        }
    }

    @Override
    public CaptureResult capture() {
        return capture(false);
    }

    @Override
    public CaptureResult captureForProcessing() {
        return capture(true);
    }

    private CaptureResult capture(boolean preferRaw) {
        // A scroll GET owns a separate long-running camera operation and must be
        // cancelled before the inherited capture path stops the control endpoint.
        try {
            scrollLiveViewReader.stop();
        } finally {
            liveViewClient.markStopped();
        }
        return preferRaw ? delegate.captureForProcessing() : delegate.capture();
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
                ? liveViewClient.supportsControl()
                        && (liveViewClient.supportsFrameRetrieval() || scrollEndpoint().isPresent())
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
        CameraCapabilitySnapshot base = delegate.capabilitySnapshot(true);
        List<CameraCapabilitySnapshot.Capability> capabilities = new ArrayList<>(base.capabilities());
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

    private Optional<String> scrollEndpoint() {
        Map<String, String> endpoints = delegate.getDiscoveredEndpoints();
        Map<String, Set<HttpMethod>> methods = delegate.getDiscoveredEndpointMethods();
        return endpoints.entrySet().stream()
                .filter(entry -> CcapiEndpointCatalog.matchesOperation(endpointPath(entry.getValue()), SCROLL_SUFFIX))
                .filter(entry -> {
                    Set<HttpMethod> advertised = methods.get(entry.getKey());
                    return advertised != null && advertised.containsAll(SCROLL_METHODS);
                })
                .max(Comparator.comparingInt(
                                (Map.Entry<String, String> entry) ->
                                        versionNumber(endpointPath(entry.getValue())))
                        .thenComparing(entry -> endpointPath(entry.getValue())))
                .map(Map.Entry::getValue);
    }

    private static CameraOperationException.Reason reason(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof CameraOperationException operation) {
                return operation.reason();
            }
            current = current.getCause();
        }
        return null;
    }

    private static String endpointPath(String value) {
        try {
            String path = URI.create(value).getPath();
            return path == null ? value : path;
        } catch (IllegalArgumentException ignored) {
            return value;
        }
    }

    private static int versionNumber(String path) {
        Matcher matcher = VERSION.matcher(path);
        if (!matcher.find()) {
            return -1;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    @Override
    public EventMode preflightNativeFocusBracketingTransport() {
        CcapiEndpointCatalog catalog = delegate.endpointCatalog();
        CanonCcapiNativeSequenceTrigger.shutterEndpoint(catalog);
        if (CcapiMediaOperation.CONTENTS.select(catalog).isEmpty()) {
            throw new NativeFocusBracketingTransportException(
                    NativeFocusBracketingTransportException.Reason.MEDIA_LISTING_UNAVAILABLE);
        }
        if (CcapiMediaOperation.MONITORING.select(catalog).isPresent()) {
            return EventMode.MONITORING;
        }
        if (CcapiMediaOperation.POLLING_CONTINUATION.select(catalog).isPresent()) {
            return EventMode.POLLING_CONTINUATION;
        }
        if (CcapiMediaOperation.POLLING.select(catalog).isPresent()) {
            return EventMode.POLLING;
        }
        throw new NativeFocusBracketingTransportException(
                NativeFocusBracketingTransportException.Reason.EVENT_TRANSPORT_UNAVAILABLE);
    }

    @Override
    public NativeFocusBracketingCapabilities getNativeFocusBracketingCapabilities() {
        return delegate.getNativeFocusBracketingCapabilities();
    }

    @Override
    public NativeFocusBracketingSession prepareNativeFocusBracketing(
            NativeFocusBracketingRequest request) {
        preflightNativeFocusBracketingTransport();
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
