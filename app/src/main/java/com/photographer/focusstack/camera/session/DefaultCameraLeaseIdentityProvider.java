package com.photographer.focusstack.camera.session;

import com.photographer.focusstack.common.model.CameraProfile;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/** Current stable identity support is intentionally limited to Canon CCAPI origins. */
@Component
public final class DefaultCameraLeaseIdentityProvider implements CameraLeaseIdentityProvider {
    @Override
    public Optional<CameraLeaseKey> resolve(CameraProfile profile) {
        if (profile == null || profile.backendType() == null) {
            return Optional.empty();
        }
        String type = profile.backendType().trim().toLowerCase(Locale.ROOT);
        return "canon-ccapi".equals(type)
                ? Optional.of(CameraLeaseKey.fromCcapiProfile(profile))
                : Optional.empty();
    }
}
