package com.photographer.focusstack.analysis.controller;

import com.photographer.focusstack.analysis.plugin.AnalysisPluginRegistry;
import com.photographer.focusstack.plugin.AnalysisContext;
import com.photographer.focusstack.plugin.AnalysisPlugin;
import com.photographer.focusstack.plugin.AnalysisPluginResult;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints for the analysis plugin system.
 *
 * <ul>
 *   <li>{@code GET  /api/analysis/plugins}                   — list registered plugins</li>
 *   <li>{@code POST /api/analysis/plugins/{name}/analyze}    — run a specific plugin</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/analysis/plugins")
public class PluginController {

    private final AnalysisPluginRegistry registry;

    public PluginController(AnalysisPluginRegistry registry) {
        this.registry = registry;
    }

    /** Lists all registered plugins with their names. */
    @GetMapping
    public ResponseEntity<List<Map<String, String>>> listPlugins() {
        List<Map<String, String>> result = registry.listAll().stream()
                .map(p -> Map.of("name", p.name()))
                .toList();
        return ResponseEntity.ok(result);
    }

    /**
     * Runs the named plugin on the supplied image.
     *
     * @param name   plugin name (URL path variable)
     * @param image  image file (JPEG or PNG recommended)
     * @param params optional key=value query parameters forwarded to the plugin context
     */
    @PostMapping(value = "/{name}/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> runPlugin(
            @PathVariable String name,
            @RequestPart("image") MultipartFile image,
            @RequestParam(required = false) Map<String, String> params) throws IOException {

        AnalysisPlugin plugin = registry.findByName(name)
                .orElse(null);
        if (plugin == null) {
            return ResponseEntity.notFound().build();
        }

        AnalysisContext context = new AnalysisContext(params != null ? params : Map.of());
        AnalysisPluginResult result = plugin.analyze(image.getBytes(), context);
        return ResponseEntity.ok(result);
    }
}
