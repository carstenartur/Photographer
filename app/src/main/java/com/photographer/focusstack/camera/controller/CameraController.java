package com.photographer.focusstack.camera.controller;

import com.photographer.focusstack.camera.backend.CameraBackendFactory;
import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.camera.CameraInfo;
import com.photographer.focusstack.common.camera.CameraState;
import com.photographer.focusstack.common.camera.CaptureResult;
import com.photographer.focusstack.common.camera.FocusCommand;
import com.photographer.focusstack.common.camera.LiveViewFrame;
import com.photographer.focusstack.common.model.CameraProfile;
import com.photographer.focusstack.camera.entity.CameraProfileEntity;
import com.photographer.focusstack.camera.search.SearchService;
import com.photographer.focusstack.camera.service.CameraProfileService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/cameras")
public class CameraController {

    private final CameraProfileService profileService;
    private final CameraBackendFactory backendFactory;
    private final SearchService searchService;

    public CameraController(CameraProfileService profileService, CameraBackendFactory backendFactory,
                            SearchService searchService) {
        this.profileService = profileService;
        this.backendFactory = backendFactory;
        this.searchService = searchService;
    }

    /** Resolve the correct backend for a camera profile. */
    private CameraBackend backendFor(String profileId) {
        CameraProfile profile = profileService.getProfile(profileId);
        return backendFactory.getBackend(profile);
    }

    @GetMapping
    public ResponseEntity<List<CameraProfile>> listProfiles() {
        return ResponseEntity.ok(profileService.getAllProfiles());
    }

    @PostMapping
    public ResponseEntity<CameraProfile> createProfile(@Valid @RequestBody CameraProfile profile) {
        return ResponseEntity.status(HttpStatus.CREATED).body(profileService.createProfile(profile));
    }

    @GetMapping("/{id}")
    public ResponseEntity<CameraProfile> getProfile(@PathVariable String id) {
        return ResponseEntity.ok(profileService.getProfile(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CameraProfile> updateProfile(@PathVariable String id,
                                                        @Valid @RequestBody CameraProfile profile) {
        backendFactory.evict(id);
        return ResponseEntity.ok(profileService.updateProfile(id, profile));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProfile(@PathVariable String id) {
        backendFactory.evict(id);
        profileService.deleteProfile(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/search")
    public ResponseEntity<List<CameraProfile>> searchProfiles(
            @RequestParam String q,
            @RequestParam(defaultValue = "20") int maxResults) {
        List<CameraProfile> results = searchService.searchCameraProfiles(q, maxResults)
                .stream()
                .map(CameraProfileEntity::toRecord)
                .toList();
        return ResponseEntity.ok(results);
    }

    @GetMapping("/{id}/info")
    public ResponseEntity<CameraInfo> getCameraInfo(@PathVariable String id) {
        return ResponseEntity.ok(backendFor(id).getInfo());
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<CameraState> getCameraStatus(@PathVariable String id) {
        return ResponseEntity.ok(backendFor(id).getState());
    }

    @GetMapping("/{id}/liveview")
    public ResponseEntity<LiveViewFrame> getLiveView(@PathVariable String id) {
        return ResponseEntity.ok(backendFor(id).getLiveView());
    }

    @PostMapping("/{id}/capture")
    public ResponseEntity<CaptureResult> capture(@PathVariable String id) {
        return ResponseEntity.ok(backendFor(id).capture());
    }

    @PostMapping("/{id}/focus")
    public ResponseEntity<Void> adjustFocus(@PathVariable String id,
                                             @Valid @RequestBody FocusCommand command) {
        backendFor(id).setFocus(command);
        return ResponseEntity.ok().build();
    }
}