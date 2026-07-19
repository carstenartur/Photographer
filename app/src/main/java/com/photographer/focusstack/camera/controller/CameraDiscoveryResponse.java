package com.photographer.focusstack.camera.controller;

import com.photographer.focusstack.camera.discovery.CcapiDiscoveredCamera;
import com.photographer.focusstack.camera.discovery.CcapiDiscoveryResult;
import com.photographer.focusstack.camera.discovery.CcapiDiscoveryWarning;

import java.util.List;

/** Stable HTTP representation of one bounded, read-only CCAPI discovery pass. */
public record CameraDiscoveryResponse(
        List<DiscoveredCamera> cameras,
        List<Warning> warnings
) {

    public CameraDiscoveryResponse {
        cameras = cameras == null ? List.of() : List.copyOf(cameras);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    static CameraDiscoveryResponse from(CcapiDiscoveryResult result) {
        return new CameraDiscoveryResponse(
                result.cameras().stream().map(DiscoveredCamera::from).toList(),
                result.warnings().stream().map(Warning::from).toList());
    }

    public record DiscoveredCamera(
            String accessUrl,
            String udn,
            String friendlyName,
            String modelName,
            String serialNumber,
            String nickname,
            boolean serviceConnected
    ) {
        private static DiscoveredCamera from(CcapiDiscoveredCamera camera) {
            return new DiscoveredCamera(
                    camera.accessUrl().toASCIIString(),
                    camera.udn(),
                    camera.friendlyName(),
                    camera.modelName(),
                    camera.serialNumber(),
                    camera.nickname(),
                    camera.serviceConnected());
        }
    }

    public record Warning(String stage, String source, String message) {
        private static Warning from(CcapiDiscoveryWarning warning) {
            return new Warning(warning.stage(), warning.source(), warning.message());
        }
    }
}
