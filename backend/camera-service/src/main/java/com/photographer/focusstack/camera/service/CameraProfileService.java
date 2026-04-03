package com.photographer.focusstack.camera.service;

import com.photographer.focusstack.common.model.CameraProfile;
import com.photographer.focusstack.camera.exception.CameraNotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CameraProfileService {

    private final ConcurrentHashMap<String, CameraProfile> profiles = new ConcurrentHashMap<>();

    public CameraProfile createProfile(CameraProfile profile) {
        profiles.put(profile.id(), profile);
        return profile;
    }

    public CameraProfile getProfile(String id) {
        CameraProfile profile = profiles.get(id);
        if (profile == null) {
            throw new CameraNotFoundException("Camera profile not found: " + id);
        }
        return profile;
    }

    public List<CameraProfile> getAllProfiles() {
        return new ArrayList<>(profiles.values());
    }

    public CameraProfile updateProfile(String id, CameraProfile profile) {
        if (!profiles.containsKey(id)) {
            throw new CameraNotFoundException("Camera profile not found: " + id);
        }
        CameraProfile updated = new CameraProfile(
                id, profile.name(), profile.backendType(), profile.host(),
                profile.defaultStepSize(), profile.maxFrames(), profile.movementThreshold()
        );
        profiles.put(id, updated);
        return updated;
    }

    public void deleteProfile(String id) {
        if (profiles.remove(id) == null) {
            throw new CameraNotFoundException("Camera profile not found: " + id);
        }
    }
}
