package com.photographer.focusstack.camera.service;

import com.photographer.focusstack.camera.entity.CameraProfileEntity;
import com.photographer.focusstack.camera.repository.CameraProfileRepository;
import com.photographer.focusstack.common.model.CameraProfile;
import com.photographer.focusstack.camera.exception.CameraNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CameraProfileService {

    private final CameraProfileRepository repository;

    public CameraProfileService(CameraProfileRepository repository) {
        this.repository = repository;
    }

    public CameraProfile createProfile(CameraProfile profile) {
        CameraProfileEntity entity = CameraProfileEntity.fromRecord(profile);
        return repository.save(entity).toRecord();
    }

    public CameraProfile getProfile(String id) {
        return repository.findById(id)
                .map(CameraProfileEntity::toRecord)
                .orElseThrow(() -> new CameraNotFoundException("Camera profile not found: " + id));
    }

    public List<CameraProfile> getAllProfiles() {
        return repository.findAll().stream()
                .map(CameraProfileEntity::toRecord)
                .toList();
    }

    public CameraProfile updateProfile(String id, CameraProfile profile) {
        if (!repository.existsById(id)) {
            throw new CameraNotFoundException("Camera profile not found: " + id);
        }
        CameraProfileEntity entity = CameraProfileEntity.fromRecord(
                new CameraProfile(id, profile.name(), profile.backendType(), profile.host(),
                        profile.defaultStepSize(), profile.maxFrames(), profile.movementThreshold())
        );
        return repository.save(entity).toRecord();
    }

    public void deleteProfile(String id) {
        if (!repository.existsById(id)) {
            throw new CameraNotFoundException("Camera profile not found: " + id);
        }
        repository.deleteById(id);
    }
}
