package com.photographer.focusstack.camera.controller;

import com.photographer.focusstack.app.api.ApiPaths;
import com.photographer.focusstack.camera.discovery.CcapiDiscoveryResult;
import com.photographer.focusstack.camera.discovery.CcapiDiscoveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/** Authenticated, read-only access to the portable Canon CCAPI discovery core. */
@RestController
@RequestMapping({ApiPaths.V1 + "/cameras/discovery", ApiPaths.LEGACY + "/cameras/discovery"})
@Validated
@Tag(name = "Camera discovery", description = "Bounded local-network discovery without profile mutation.")
public class CameraDiscoveryController {

    static final int DEFAULT_TIMEOUT_SECONDS = 5;
    static final int MAX_TIMEOUT_SECONDS = 30;

    private final CcapiDiscoveryService discoveryService;

    public CameraDiscoveryController(CcapiDiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    @PostMapping
    @Operation(summary = "Discover Canon CCAPI cameras on eligible local-network interfaces")
    public ResponseEntity<CameraDiscoveryResponse> discover(
            @RequestParam(required = false)
            @Min(1) @Max(MAX_TIMEOUT_SECONDS) Integer timeoutSeconds) {
        int effectiveTimeout = timeoutSeconds == null
                ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds;
        CcapiDiscoveryResult result = discoveryService.discover(
                Duration.ofSeconds(effectiveTimeout));
        return ResponseEntity.ok(CameraDiscoveryResponse.from(result));
    }
}
