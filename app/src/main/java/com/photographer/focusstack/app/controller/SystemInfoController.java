package com.photographer.focusstack.app.controller;

import com.photographer.focusstack.app.api.ApiPaths;
import com.photographer.focusstack.camera.service.CameraProfileService;
import com.photographer.focusstack.camera.service.DirectCameraControlExecutor;
import com.photographer.focusstack.camera.session.CameraSessionConflictException;
import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.camera.CameraInfo;
import com.photographer.focusstack.common.model.CameraProfile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({ApiPaths.V1 + "/system", ApiPaths.LEGACY + "/system"})
@Tag(name = "System", description = "System diagnostics and environment overview endpoints.")
public class SystemInfoController {

    private static final Logger log = LoggerFactory.getLogger(SystemInfoController.class);

    private final CameraProfileService profileService;
    private final DirectCameraControlExecutor directControl;

    public SystemInfoController(CameraProfileService profileService, DirectCameraControlExecutor directControl) {
        this.profileService = profileService;
        this.directControl = directControl;
    }

    @GetMapping("/info")
    @Operation(summary = "Get runtime system information")
    public ResponseEntity<Map<String, Object>> getSystemInfo() {
        Map<String, Object> info = new LinkedHashMap<>();

        // JVM memory
        Runtime rt = Runtime.getRuntime();
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("totalMb", rt.totalMemory() / (1024 * 1024));
        memory.put("usedMb", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
        memory.put("maxMb", rt.maxMemory() / (1024 * 1024));
        info.put("memory", memory);

        // Disk space (all roots)
        List<Map<String, Object>> disks = new ArrayList<>();
        for (File root : File.listRoots()) {
            Map<String, Object> disk = new LinkedHashMap<>();
            disk.put("path", root.getAbsolutePath());
            disk.put("totalGb", root.getTotalSpace() / (1024 * 1024 * 1024));
            disk.put("freeGb", root.getFreeSpace() / (1024 * 1024 * 1024));
            disk.put("usableGb", root.getUsableSpace() / (1024 * 1024 * 1024));
            disks.add(disk);
        }
        info.put("disks", disks);

        // Connected cameras
        List<Map<String, Object>> cameras = new ArrayList<>();
        try {
            List<CameraProfile> profiles = profileService.getAllProfiles();
            for (CameraProfile profile : profiles) {
                Map<String, Object> cam = new LinkedHashMap<>();
                cam.put("id", profile.id());
                cam.put("name", profile.name());
                cam.put("backendType", profile.backendType());
                try {
                    CameraInfo cameraInfo = directControl.withBackend(profile.id(), CameraBackend::getInfo);
                    cam.put("connected", true);
                    cam.put("model", cameraInfo.model());
                    cam.put("firmware", cameraInfo.firmwareVersion());
                } catch (Exception e) {
                    cam.put("connected", false);
                    if (e instanceof CameraSessionConflictException) {
                        cam.put("busy", true);
                    }
                    cam.put("error", e.getMessage());
                }
                cameras.add(cam);
            }
        } catch (Exception e) {
            log.warn("Failed to query camera profiles: {}", e.getMessage());
        }
        info.put("cameras", cameras);

        // Uptime
        long uptimeMs = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime();
        info.put("uptimeSeconds", uptimeMs / 1000);

        return ResponseEntity.ok(info);
    }
}
