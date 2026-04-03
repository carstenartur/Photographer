package com.photographer.focusstack.camera.controller;

import com.canon.focusstack.common.camera.CameraBackend;
import com.canon.focusstack.common.camera.CameraState;
import com.canon.focusstack.common.camera.CaptureResult;
import com.canon.focusstack.common.camera.FocusCommand;
import com.canon.focusstack.common.camera.LiveViewFrame;
import com.canon.focusstack.common.model.CameraProfile;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/cameras")
public class CameraController {

    private final CameraProfileService profileService;
    private final CameraBackend cameraBackend;

    public CameraController(CameraProfileService profileService, CameraBackend cameraBackend) {
        this.profileService = profileService;
        this.cameraBackend = cameraBackend;
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
        return ResponseEntity.ok(profileService.updateProfile(id, profile));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProfile(@PathVariable String id) {
        profileService.deleteProfile(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<CameraState> getCameraStatus(@PathVariable String id) {
        profileService.getProfile(id);
        return ResponseEntity.ok(cameraBackend.getState());
    }

    @GetMapping("/{id}/liveview")
    public ResponseEntity<LiveViewFrame> getLiveView(@PathVariable String id) {
        profileService.getProfile(id);
        return ResponseEntity.ok(cameraBackend.getLiveView());
    }

    @PostMapping("/{id}/capture")
    public ResponseEntity<CaptureResult> capture(@PathVariable String id) {
        profileService.getProfile(id);
        return ResponseEntity.ok(cameraBackend.capture());
    }

    @PostMapping("/{id}/focus")
    public ResponseEntity<Void> adjustFocus(@PathVariable String id,
                                             @Valid @RequestBody FocusCommand command) {
        profileService.getProfile(id);
        cameraBackend.setFocus(command);
        return ResponseEntity.ok().build();
    }
}
