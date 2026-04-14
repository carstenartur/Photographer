package com.photographer.focusstack.analysis.plugin;

import com.photographer.focusstack.analysis.service.ImageJProcessor;
import com.photographer.focusstack.analysis.service.SharpnessAnalyzer;
import com.photographer.focusstack.plugin.AnalysisContext;
import com.photographer.focusstack.plugin.AnalysisPlugin;
import com.photographer.focusstack.plugin.AnalysisPluginResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.pf4j.PluginManager;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisPluginRegistryTest {

    @Mock private ImageJProcessor imageJProcessor;
    @Mock private SharpnessAnalyzer sharpnessAnalyzer;
    @Mock private PluginManager pluginManager;

    private AnalysisPluginRegistry buildRegistry() {
        DefaultSharpnessPlugin plugin = new DefaultSharpnessPlugin(imageJProcessor, sharpnessAnalyzer);
        when(pluginManager.getExtensions(AnalysisPlugin.class)).thenReturn(List.of());
        return new AnalysisPluginRegistry(List.of(plugin), pluginManager);
    }

    @Test
    void listAll_returnsRegisteredPlugins() {
        AnalysisPluginRegistry registry = buildRegistry();
        List<AnalysisPlugin> plugins = registry.listAll();
        assertFalse(plugins.isEmpty());
        assertEquals(1, plugins.size());
        assertEquals("sharpness", plugins.get(0).name());
    }

    @Test
    void findByName_returnsPluginForKnownName() {
        AnalysisPluginRegistry registry = buildRegistry();
        Optional<AnalysisPlugin> plugin = registry.findByName("sharpness");
        assertTrue(plugin.isPresent());
        assertEquals("sharpness", plugin.get().name());
    }

    @Test
    void findByName_returnsEmptyForUnknownName() {
        AnalysisPluginRegistry registry = buildRegistry();
        Optional<AnalysisPlugin> plugin = registry.findByName("nonexistent");
        assertTrue(plugin.isEmpty());
    }

    @Test
    void defaultSharpnessPlugin_analyzesWithFallback() {
        when(imageJProcessor.isImageFile(any())).thenReturn(false);
        when(sharpnessAnalyzer.calculateSharpness(any())).thenReturn(0.75);

        AnalysisPluginRegistry registry = buildRegistry();
        AnalysisPlugin plugin = registry.findByName("sharpness").orElseThrow();
        AnalysisPluginResult result = plugin.analyze(new byte[]{1, 2, 3}, AnalysisContext.empty());

        assertNotNull(result);
        assertEquals("sharpness", result.pluginName());
        assertEquals(0.75, result.score(), 0.001);
        assertEquals("sharpness", result.label());
    }

    @Test
    void duplicatePluginNames_throwsIllegalStateException() {
        // Two plugins with the same name should cause startup failure with a clear message
        AnalysisPlugin duplicate1 = new AnalysisPlugin() {
            @Override public String name() { return "sharpness"; }
            @Override public AnalysisPluginResult analyze(byte[] d, AnalysisContext c) { return null; }
        };
        AnalysisPlugin duplicate2 = new AnalysisPlugin() {
            @Override public String name() { return "sharpness"; }
            @Override public AnalysisPluginResult analyze(byte[] d, AnalysisContext c) { return null; }
        };

        when(pluginManager.getExtensions(AnalysisPlugin.class)).thenReturn(List.of());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new AnalysisPluginRegistry(List.of(duplicate1, duplicate2), pluginManager));
        assertTrue(ex.getMessage().contains("sharpness"),
                "Exception message should identify the duplicate plugin name");
    }

    @Test
    void analysisContext_getOrDefault_returnsDefaultWhenKeyAbsent() {
        AnalysisContext ctx = AnalysisContext.empty();
        assertEquals("default", ctx.getOrDefault("missingKey", "default"));
    }
}
