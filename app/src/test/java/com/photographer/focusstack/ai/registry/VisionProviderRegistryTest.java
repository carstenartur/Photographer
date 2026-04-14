package com.photographer.focusstack.ai.registry;

import com.photographer.focusstack.ai.ProviderCapabilities;
import com.photographer.focusstack.ai.VisionContext;
import com.photographer.focusstack.ai.VisionProvider;
import com.photographer.focusstack.ai.VisionResult;
import com.photographer.focusstack.app.plugin.FocusStackPluginManager;
import com.photographer.focusstack.plugin.PluginMetadata;
import com.photographer.focusstack.plugin.VisionProviderPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VisionProviderRegistryTest {

    @Mock private FocusStackPluginManager pluginManager;

    @Test
    void listAll_returnsOnlyBuiltInWhenNoExternalPlugins() {
        VisionProvider mockProvider = createStubProvider("mock");
        when(pluginManager.getExtensions(VisionProviderPlugin.class)).thenReturn(List.of());

        VisionProviderRegistry registry = new VisionProviderRegistry(List.of(mockProvider), pluginManager);
        List<VisionProvider> all = registry.listAll();
        assertEquals(1, all.size());
        assertEquals("mock", all.get(0).name());
    }

    @Test
    void listAll_mergesBuiltInAndExternalPlugins() {
        VisionProvider builtIn = createStubProvider("gemini");
        VisionProviderPlugin externalPlugin = createStubPlugin("openai");
        when(pluginManager.getExtensions(VisionProviderPlugin.class))
                .thenReturn(List.of())  // constructor call
                .thenReturn(List.of(externalPlugin));  // listAll call

        VisionProviderRegistry registry = new VisionProviderRegistry(List.of(builtIn), pluginManager);
        List<VisionProvider> all = registry.listAll();
        assertEquals(2, all.size());
        assertEquals("gemini", all.get(0).name());
        assertEquals("openai", all.get(1).name());
    }

    @Test
    void findByName_returnsBuiltInProvider() {
        VisionProvider builtIn = createStubProvider("gemini");
        when(pluginManager.getExtensions(VisionProviderPlugin.class)).thenReturn(List.of());

        VisionProviderRegistry registry = new VisionProviderRegistry(List.of(builtIn), pluginManager);
        Optional<VisionProvider> found = registry.findByName("gemini");
        assertTrue(found.isPresent());
        assertEquals("gemini", found.get().name());
    }

    @Test
    void findByName_returnsExternalPlugin() {
        VisionProviderPlugin plugin = createStubPlugin("openai");
        when(pluginManager.getExtensions(VisionProviderPlugin.class))
                .thenReturn(List.of())  // constructor call
                .thenReturn(List.of(plugin));  // findByName call

        VisionProviderRegistry registry = new VisionProviderRegistry(List.of(), pluginManager);
        Optional<VisionProvider> found = registry.findByName("openai");
        assertTrue(found.isPresent());
        assertEquals("openai", found.get().name());
    }

    @Test
    void findByName_caseInsensitive() {
        VisionProvider builtIn = createStubProvider("Gemini");
        when(pluginManager.getExtensions(VisionProviderPlugin.class)).thenReturn(List.of());

        VisionProviderRegistry registry = new VisionProviderRegistry(List.of(builtIn), pluginManager);
        Optional<VisionProvider> found = registry.findByName("gemini");
        assertTrue(found.isPresent());
    }

    @Test
    void findByName_returnsEmptyForUnknown() {
        when(pluginManager.getExtensions(VisionProviderPlugin.class)).thenReturn(List.of());

        VisionProviderRegistry registry = new VisionProviderRegistry(List.of(), pluginManager);
        Optional<VisionProvider> found = registry.findByName("unknown");
        assertTrue(found.isEmpty());
    }

    @Test
    void listAvailable_filtersUnavailable() {
        VisionProvider available = createStubProvider("gemini", true);
        VisionProvider unavailable = createStubProvider("disabled", false);
        when(pluginManager.getExtensions(VisionProviderPlugin.class)).thenReturn(List.of());

        VisionProviderRegistry registry = new VisionProviderRegistry(
                List.of(available, unavailable), pluginManager);
        List<VisionProvider> result = registry.listAvailable();
        assertEquals(1, result.size());
        assertEquals("gemini", result.get(0).name());
    }

    @Test
    void listProviderNames_returnsAllNames() {
        VisionProvider builtIn = createStubProvider("gemini");
        VisionProviderPlugin plugin = createStubPlugin("openai");
        when(pluginManager.getExtensions(VisionProviderPlugin.class))
                .thenReturn(List.of())  // constructor
                .thenReturn(List.of(plugin));  // listProviderNames → listAll

        VisionProviderRegistry registry = new VisionProviderRegistry(List.of(builtIn), pluginManager);
        List<String> names = registry.listProviderNames();
        assertEquals(2, names.size());
        assertTrue(names.contains("gemini"));
        assertTrue(names.contains("openai"));
    }

    private static VisionProvider createStubProvider(String name) {
        return createStubProvider(name, true);
    }

    private static VisionProvider createStubProvider(String name, boolean available) {
        return new VisionProvider() {
            @Override public String name() { return name; }
            @Override public VisionResult classifyImage(byte[] d, VisionContext c) { return null; }
            @Override public VisionResult describeImage(byte[] d, VisionContext c) { return null; }
            @Override public VisionResult detectObjects(byte[] d, VisionContext c) { return null; }
            @Override public VisionResult rankBestImage(List<byte[]> i, VisionContext c) { return null; }
            @Override public boolean isAvailable() { return available; }
            @Override public ProviderCapabilities capabilities() { return null; }
        };
    }

    private static VisionProviderPlugin createStubPlugin(String name) {
        return new VisionProviderPlugin() {
            @Override public PluginMetadata metadata() {
                return PluginMetadata.of(name, name + " provider", "1.0.0");
            }
            @Override public String name() { return name; }
            @Override public VisionResult classifyImage(byte[] d, VisionContext c) { return null; }
            @Override public VisionResult describeImage(byte[] d, VisionContext c) { return null; }
            @Override public VisionResult detectObjects(byte[] d, VisionContext c) { return null; }
            @Override public VisionResult rankBestImage(List<byte[]> i, VisionContext c) { return null; }
            @Override public boolean isAvailable() { return true; }
            @Override public ProviderCapabilities capabilities() { return null; }
        };
    }
}
