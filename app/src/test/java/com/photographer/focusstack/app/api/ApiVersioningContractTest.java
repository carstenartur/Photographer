package com.photographer.focusstack.app.api;

import com.photographer.focusstack.analysis.controller.AnalysisController;
import com.photographer.focusstack.analysis.controller.PluginController;
import com.photographer.focusstack.analysis.controller.StackMergeController;
import com.photographer.focusstack.app.controller.SystemInfoController;
import com.photographer.focusstack.app.plugin.PluginManagementController;
import com.photographer.focusstack.astro.controller.AstroController;
import com.photographer.focusstack.camera.controller.CameraController;
import com.photographer.focusstack.camera.controller.CameraDiscoveryController;
import com.photographer.focusstack.camera.controller.FaceOrbitController;
import com.photographer.focusstack.camera.controller.HdrTimelapseController;
import com.photographer.focusstack.camera.controller.JobController;
import com.photographer.focusstack.observer.controller.ObserverController;
import com.photographer.focusstack.observer.controller.ObserverExportController;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiVersioningContractTest {

    private static final List<Class<?>> REST_CONTROLLERS = List.of(
            AnalysisController.class,
            PluginController.class,
            StackMergeController.class,
            SystemInfoController.class,
            PluginManagementController.class,
            AstroController.class,
            CameraController.class,
            CameraDiscoveryController.class,
            FaceOrbitController.class,
            HdrTimelapseController.class,
            JobController.class,
            ObserverController.class,
            ObserverExportController.class);

    @Test
    void everyRestControllerMapsCanonicalV1AndEquivalentLegacyAlias() {
        for (Class<?> controller : REST_CONTROLLERS) {
            RequestMapping mapping = controller.getAnnotation(RequestMapping.class);
            assertNotNull(mapping, () -> controller.getName() + " has no class mapping");

            Set<String> paths = Arrays.stream(mapping.value())
                    .collect(Collectors.toUnmodifiableSet());
            assertEquals(2, paths.size(),
                    () -> controller.getSimpleName() + " must expose exactly v1 and legacy roots");

            String canonical = paths.stream()
                    .filter(path -> path.startsWith(ApiPaths.V1 + "/"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            controller.getSimpleName() + " has no canonical v1 mapping: " + paths));
            String suffix = canonical.substring(ApiPaths.V1.length());
            assertTrue(paths.contains(ApiPaths.LEGACY + suffix),
                    () -> controller.getSimpleName()
                            + " has no equivalent legacy alias for " + canonical + ": " + paths);
        }
    }

    @Test
    void realtimeContractHasAnIndependentExplicitVersionBoundary() {
        assertEquals(1, RealtimeApiPaths.SCHEMA_VERSION);
        assertEquals("/ws", RealtimeApiPaths.STOMP_ENDPOINT);
        assertEquals("/ws/plain", RealtimeApiPaths.PLAIN_ENDPOINT);
        assertTrue(!RealtimeApiPaths.STOMP_ENDPOINT.startsWith(ApiPaths.LEGACY),
                "WebSocket transport is versioned independently from REST paths");
    }
}
