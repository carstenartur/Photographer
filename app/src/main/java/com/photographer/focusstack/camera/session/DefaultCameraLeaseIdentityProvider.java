package com.photographer.focusstack.camera.session;

import com.photographer.focusstack.common.model.CameraProfile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/** Maps CCAPI origins, Canon USB discovery, and local mock profiles when paired capture is enabled. */
@Component
public final class DefaultCameraLeaseIdentityProvider implements CameraLeaseIdentityProvider {
    private final boolean managedMocks;

    public DefaultCameraLeaseIdentityProvider() {
        this(false);
    }

    @Autowired
    public DefaultCameraLeaseIdentityProvider(@Value("${focusstack.fresnel.enabled:false}") boolean managedMocks) {
        this.managedMocks = managedMocks;
    }

    @Override
    public Optional<CameraLeaseKey> resolve(CameraProfile profile) {
        if (profile == null || profile.backendType() == null) {
            return Optional.empty();
        }
        String type = profile.backendType().trim().toLowerCase(Locale.ROOT);
        if ("canon-usb".equals(type)) {
            // The backend scans for the first Canon device. Profiles have no USB device selector.
            return Optional.of(CameraLeaseKey.FIRST_CANON_USB_CAMERA);
        }
        if (managedMocks && "mock".equals(type)) {
            return Optional.of(CameraLeaseKey.fromMockProfile(profile.id()));
        }
        return "canon-ccapi".equals(type)
                ? Optional.of(CameraLeaseKey.fromCcapiProfile(profile))
                : Optional.empty();
    }
}
