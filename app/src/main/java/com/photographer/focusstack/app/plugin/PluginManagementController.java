package com.photographer.focusstack.app.plugin;

import com.photographer.focusstack.app.api.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints for managing Focus Stack plugins (install, enable, disable, uninstall).
 *
 * <p>All endpoints are under {@code /api/admin/plugins} and require the {@code ADMIN} role
 * (enforced by the security filter chain matching {@code /api/admin/**}).
 *
 * <ul>
 *   <li>{@code GET    /api/admin/plugins}                — list all installed plugins</li>
 *   <li>{@code GET    /api/admin/plugins/{id}}            — get details of a plugin</li>
 *   <li>{@code POST   /api/admin/plugins/install}         — upload and install a plugin JAR</li>
 *   <li>{@code POST   /api/admin/plugins/{id}/enable}     — enable (start) a plugin</li>
 *   <li>{@code POST   /api/admin/plugins/{id}/disable}    — disable (stop) a plugin</li>
 *   <li>{@code DELETE  /api/admin/plugins/{id}}            — uninstall a plugin</li>
 * </ul>
 */
@RestController
@RequestMapping({ApiPaths.V1 + "/admin/plugins", ApiPaths.LEGACY + "/admin/plugins"})
@Tag(name = "Plugin Management", description = "Administrative plugin lifecycle endpoints.")
public class PluginManagementController {

    private final PluginManagementService pluginService;

    public PluginManagementController(PluginManagementService pluginService) {
        this.pluginService = pluginService;
    }

    /** Lists all installed plugins with their metadata and state. */
    @GetMapping
    @Operation(summary = "List installed plugins")
    public ResponseEntity<List<Map<String, Object>>> listPlugins() {
        return ResponseEntity.ok(pluginService.listPlugins());
    }

    /** Returns details for a single plugin by ID. */
    @GetMapping("/{id}")
    @Operation(summary = "Get plugin details by id")
    public ResponseEntity<Map<String, Object>> getPlugin(@PathVariable String id) {
        return pluginService.getPlugin(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Uploads and installs a plugin JAR.
     *
     * @param file the plugin JAR file
     */
    @PostMapping(value = "/install", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Install plugin from uploaded JAR")
    public ResponseEntity<Map<String, String>> installPlugin(
            @RequestPart("file") MultipartFile file) throws IOException {

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File name must not be empty"));
        }

        try (InputStream inputStream = file.getInputStream()) {
            String pluginId = pluginService.installPlugin(originalFilename, inputStream);
            return ResponseEntity.ok(Map.of(
                    "pluginId", pluginId,
                    "status", "installed"));
        }
    }

    /** Enables (starts) a plugin by ID. */
    @PostMapping("/{id}/enable")
    @Operation(summary = "Enable plugin")
    public ResponseEntity<Map<String, String>> enablePlugin(@PathVariable String id) {
        boolean success = pluginService.enablePlugin(id);
        if (!success) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("pluginId", id, "status", "enabled"));
    }

    /** Disables (stops) a plugin by ID. */
    @PostMapping("/{id}/disable")
    @Operation(summary = "Disable plugin")
    public ResponseEntity<Map<String, String>> disablePlugin(@PathVariable String id) {
        boolean success = pluginService.disablePlugin(id);
        if (!success) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("pluginId", id, "status", "disabled"));
    }

    /** Uninstalls a plugin by ID. */
    @DeleteMapping("/{id}")
    @Operation(summary = "Uninstall plugin")
    public ResponseEntity<Map<String, String>> uninstallPlugin(@PathVariable String id) {
        boolean success = pluginService.uninstallPlugin(id);
        if (!success) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("pluginId", id, "status", "uninstalled"));
    }

    /** Maps validation errors from the service layer to HTTP 400 responses. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
