package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.app.plugin.FocusStackPluginManager;
import com.photographer.focusstack.common.camera.*;
import com.photographer.focusstack.plugin.CameraBackendPlugin;
import com.photographer.focusstack.plugin.PluginMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CameraBackendRegistryTest {

    @Mock private FocusStackPluginManager pluginManager;

    @Test
    void getExternalBackends_emptyWhenNoPlugins() {
        when(pluginManager.getExtensions(CameraBackendPlugin.class)).thenReturn(List.of());

        CameraBackendRegistry registry = new CameraBackendRegistry(pluginManager);
        Map<String, CameraBackendPlugin> backends = registry.getExternalBackends();
        assertTrue(backends.isEmpty());
    }

    @Test
    void getExternalBackends_returnsDiscoveredPlugins() {
        CameraBackendPlugin sonyPlugin = createStubPlugin("sony-wifi");
        when(pluginManager.getExtensions(CameraBackendPlugin.class)).thenReturn(List.of(sonyPlugin));

        CameraBackendRegistry registry = new CameraBackendRegistry(pluginManager);
        Map<String, CameraBackendPlugin> backends = registry.getExternalBackends();
        assertEquals(1, backends.size());
        assertTrue(backends.containsKey("sony-wifi"));
    }

    @Test
    void findByType_returnsPluginForKnownType() {
        CameraBackendPlugin plugin = createStubPlugin("nikon-http");
        when(pluginManager.getExtensions(CameraBackendPlugin.class)).thenReturn(List.of(plugin));

        CameraBackendRegistry registry = new CameraBackendRegistry(pluginManager);
        Optional<CameraBackendPlugin> found = registry.findByType("nikon-http");
        assertTrue(found.isPresent());
        assertEquals("nikon-http", found.get().backendType());
    }

    @Test
    void findByType_returnsEmptyForUnknownType() {
        when(pluginManager.getExtensions(CameraBackendPlugin.class)).thenReturn(List.of());

        CameraBackendRegistry registry = new CameraBackendRegistry(pluginManager);
        Optional<CameraBackendPlugin> found = registry.findByType("unknown");
        assertTrue(found.isEmpty());
    }

    @Test
    void listBackendTypes_returnsAllTypes() {
        CameraBackendPlugin p1 = createStubPlugin("sony-wifi");
        CameraBackendPlugin p2 = createStubPlugin("gphoto2");
        when(pluginManager.getExtensions(CameraBackendPlugin.class)).thenReturn(List.of(p1, p2));

        CameraBackendRegistry registry = new CameraBackendRegistry(pluginManager);
        List<String> types = registry.listBackendTypes();
        assertEquals(2, types.size());
        assertTrue(types.contains("sony-wifi"));
        assertTrue(types.contains("gphoto2"));
    }

    @Test
    void getExternalBackends_handlesDuplicateBackendTypes() {
        CameraBackendPlugin first = createStubPlugin("sony-wifi");
        CameraBackendPlugin duplicate = createStubPlugin("sony-wifi");
        when(pluginManager.getExtensions(CameraBackendPlugin.class)).thenReturn(List.of(first, duplicate));

        CameraBackendRegistry registry = new CameraBackendRegistry(pluginManager);
        Map<String, CameraBackendPlugin> backends = registry.getExternalBackends();
        // First one wins, duplicate is ignored
        assertEquals(1, backends.size());
        assertSame(first, backends.get("sony-wifi"));
    }

    /** Creates a minimal stub CameraBackendPlugin for testing. */
    private static CameraBackendPlugin createStubPlugin(String type) {
        return new CameraBackendPlugin() {
            @Override public PluginMetadata metadata() {
                return PluginMetadata.of(type, type + " camera", "1.0.0");
            }
            @Override public String backendType() { return type; }
            @Override public CameraInfo getInfo() { return null; }
            @Override public LiveViewFrame getLiveView() { return null; }
            @Override public CaptureResult capture() { return null; }
            @Override public void setFocus(FocusCommand command) {}
            @Override public CameraState getState() { return null; }
            @Override public boolean supports(Operation operation) { return false; }
        };
    }
}
