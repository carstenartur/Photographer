package com.photographer.focusstack.camera.controller;

import com.photographer.focusstack.camera.backend.CameraBackendFactory;
import com.photographer.focusstack.camera.entity.CameraProfileEntity;
import com.photographer.focusstack.camera.search.SearchService;
import com.photographer.focusstack.camera.service.CameraProfileService;
import com.photographer.focusstack.camera.service.DirectCameraControlExecutor;
import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.camera.CameraCapabilityProvider;
import com.photographer.focusstack.common.camera.CameraCapabilitySnapshot;
import com.photographer.focusstack.common.camera.CameraInfo;
import com.photographer.focusstack.common.camera.CameraState;
import com.photographer.focusstack.common.camera.CaptureResult;
import com.photographer.focusstack.common.camera.FocusCommand;
import com.photographer.focusstack.common.camera.LiveViewFrame;
import com.photographer.focusstack.common.model.CameraProfile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Cameras", description = "Camera profile management and live camera control endpoints.")
public class CameraController {

    private final CameraProfileService profileService;
    private final CameraBackendFactory backendFactory;
    private final SearchService searchService;
    private final DirectCameraControlExecutor directControl;

    public CameraController(
            CameraProfileService profileService,
            CameraBackendFactory backendFactory,
            SearchService searchService,
            DirectCameraControlExecutor directControl) {
        this.profileService = profileService;
        this.backendFactory = backendFactory;
        this.searchService = searchService;
        this.directControl = directControl;
    }

    /** Read-only access remains on the existing cached backend boundary. */
    private CameraBackend backendForRead(String profileId) {
        CameraProfile profile = profileService.getProfile(profileId);
        return backendFactory.getBackend(profile);
    }

    @GetMapping
    @Operation(summary = "List camera profiles")
    public ResponseEntity<List<CameraProfile>> listProfiles() {
        return ResponseEntity.ok(profileService.getAllProfiles());
    }

    @PostMapping
    @Operation(summary = "Create a camera profile")
    public ResponseEntity<CameraProfile> createProfile(
            @Valid @RequestBody CameraProfile profile) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(profileService.createProfile(profile));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get camera profile by id")
    public ResponseEntity<CameraProfile> getProfile(@PathVariable String id) {
        return ResponseEntity.ok(profileService.getProfile(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update camera profile by id")
    public ResponseEntity<CameraProfile> updateProfile(
            @PathVariable String id,
            @Valid @RequestBody CameraProfile profile) {
        CameraProfile updated = directControl.withProfileLease(id, () -> {
            backendFactory.evict(id);
            return profileService.updateProfile(id, profile);
        });
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete camera profile by id")
    public ResponseEntity<Void> deleteProfile(@PathVariable String id) {
        directControl.withProfileLease(id, () -> {
            backendFactory.evict(id);
            profileService.deleteProfile(id);
        });
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/search")
    @Operation(summary = "Search camera profiles")
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
    @Operation(summary = "Get camera hardware information")
    public ResponseEntity<CameraInfo> getCameraInfo(@PathVariable String id) {
        return ResponseEntity.ok(backendForRead(id).getInfo());
    }

    @GetMapping("/{id}/status")
    @Operation(summary = "Get camera status")
    public ResponseEntity<CameraState> getCameraStatus(@PathVariable String id) {
        return ResponseEntity.ok(backendForRead(id).getState());
    }

    @GetMapping("/{id}/capabilities")
    @Operation(summary = "Get redacted camera capability diagnostics")
    public ResponseEntity<CameraCapabilitySnapshot> getCameraCapabilities(
            @PathVariable String id) {
        CameraBackend backend = backendForRead(id);
        CameraCapabilitySnapshot snapshot = backend instanceof CameraCapabilityProvider provider
                ? provider.getCapabilitySnapshot()
                : CameraCapabilitySnapshot.diagnosticsUnavailable(backend.getInfo());
        return ResponseEntity.ok(snapshot);
    }

    @GetMapping("/{id}/liveview")
    @Operation(summary = "Get latest live view frame")
    public ResponseEntity<LiveViewFrame> getLiveView(@PathVariable String id) {
        return ResponseEntity.ok(backendForRead(id).getLiveView());
    }

    @PostMapping("/{id}/capture")
    @Operation(summary = "Capture a frame")
    public ResponseEntity<CaptureResult> capture(@PathVariable String id) {
        return ResponseEntity.ok(directControl.withBackend(id, CameraBackend::capture));
    }

    @PostMapping("/{id}/focus")
    @Operation(summary = "Adjust camera focus")
    public ResponseEntity<Void> adjustFocus(
            @PathVariable String id,
            @Valid @RequestBody FocusCommand command) {
        directControl.withBackend(id, backend -> backend.setFocus(command));
        return ResponseEntity.ok().build();
    }
}
