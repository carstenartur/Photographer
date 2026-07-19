package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.camera.CameraCapabilityProvider;
import com.photographer.focusstack.common.camera.CameraCapabilitySnapshot;
import com.photographer.focusstack.common.camera.CameraInfo;
import com.photographer.focusstack.common.camera.CameraMediaProvider;
import com.photographer.focusstack.common.camera.CameraMediaReference;
import com.photographer.focusstack.common.camera.CameraState;
import com.photographer.focusstack.common.camera.CaptureResult;
import com.photographer.focusstack.common.camera.ExposureInfo;
import com.photographer.focusstack.common.camera.FocusCommand;
import com.photographer.focusstack.common.camera.LiveViewFrame;
import com.photographer.focusstack.common.camera.NativeFocusBracketingCapabilities;
import com.photographer.focusstack.common.camera.NativeFocusBracketingProvider;
import com.photographer.focusstack.common.camera.NativeFocusBracketingRequest;
import com.photographer.focusstack.common.camera.NativeFocusBracketingSession;
import com.photographer.focusstack.common.camera.Operation;
import com.photographer.focusstack.common.camera.CameraStatusProvider;
import com.photographer.focusstack.common.device.BatteryInfo;
import com.photographer.focusstack.common.device.StorageInfo;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

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
        return delegate.getCapabilitySnapshot();
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
