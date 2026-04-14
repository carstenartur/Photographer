package com.photographer.focusstack.app.plugin;

import com.photographer.focusstack.app.config.PluginProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PluginManagementServiceTest {

    @Mock private FocusStackPluginManager pluginManager;

    @TempDir
    Path tempDir;

    private PluginManagementService service;

    @BeforeEach
    void setUp() {
        PluginProperties props = new PluginProperties(tempDir, true);
        service = new PluginManagementService(pluginManager, props);
    }

    @Test
    void listPlugins_returnsEmptyWhenNoPlugins() {
        when(pluginManager.getPlugins()).thenReturn(List.of());
        List<Map<String, Object>> result = service.listPlugins();
        assertTrue(result.isEmpty());
    }

    @Test
    void listPlugins_returnsPluginInfo() {
        FocusStackPluginManager.PluginInfo info =
                new FocusStackPluginManager.PluginInfo("test-plugin", "1.0.0", "Test", "Author", "MIT");
        FocusStackPluginManager.LoadedPlugin loaded =
                new FocusStackPluginManager.LoadedPlugin(info, tempDir.resolve("test.jar"), null, true);
        when(pluginManager.getPlugins()).thenReturn(List.of(loaded));

        List<Map<String, Object>> result = service.listPlugins();
        assertEquals(1, result.size());
        assertEquals("test-plugin", result.get(0).get("id"));
        assertEquals("ENABLED", result.get(0).get("state"));
    }

    @Test
    void getPlugin_returnsEmptyForUnknown() {
        when(pluginManager.getPlugin("unknown")).thenReturn(Optional.empty());
        Optional<Map<String, Object>> result = service.getPlugin("unknown");
        assertTrue(result.isEmpty());
    }

    @Test
    void installPlugin_rejectsNonJarFile() {
        assertThrows(IllegalArgumentException.class,
                () -> service.installPlugin("malware.exe",
                        new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void installPlugin_rejectsPathTraversal() {
        assertThrows(IllegalArgumentException.class,
                () -> service.installPlugin("../evil.jar",
                        new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void installPlugin_rejectsEmptyFileName() {
        assertThrows(IllegalArgumentException.class,
                () -> service.installPlugin("",
                        new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void installPlugin_savesAndLoadsJar() throws IOException {
        when(pluginManager.loadPlugin(any(Path.class))).thenReturn("new-plugin");

        String pluginId = service.installPlugin("test-plugin.jar",
                new ByteArrayInputStream(new byte[]{1, 2, 3}));

        assertEquals("new-plugin", pluginId);
        verify(pluginManager).loadPlugin(any(Path.class));
    }

    @Test
    void enablePlugin_returnsFalseForUnknown() {
        when(pluginManager.enablePlugin("unknown")).thenReturn(false);
        assertFalse(service.enablePlugin("unknown"));
    }

    @Test
    void enablePlugin_delegatesToManager() {
        when(pluginManager.enablePlugin("test")).thenReturn(true);
        assertTrue(service.enablePlugin("test"));
        verify(pluginManager).enablePlugin("test");
    }

    @Test
    void disablePlugin_returnsFalseForUnknown() {
        when(pluginManager.disablePlugin("unknown")).thenReturn(false);
        assertFalse(service.disablePlugin("unknown"));
    }

    @Test
    void uninstallPlugin_returnsFalseForUnknown() {
        when(pluginManager.deletePlugin("unknown")).thenReturn(false);
        assertFalse(service.uninstallPlugin("unknown"));
    }

    @Test
    void uninstallPlugin_delegatesToManager() {
        when(pluginManager.deletePlugin("test")).thenReturn(true);
        assertTrue(service.uninstallPlugin("test"));
        verify(pluginManager).deletePlugin("test");
    }
}
