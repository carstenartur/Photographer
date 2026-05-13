package com.photographer.focusstack.astro.controller;

import com.photographer.focusstack.astro.service.AstroModeService;
import com.photographer.focusstack.common.model.AstroCaptureConfig;
import com.photographer.focusstack.common.model.AstroFocusConfig;
import com.photographer.focusstack.common.model.AstroSession;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/astro")
@Tag(name = "Astro", description = "Astro capture session lifecycle endpoints.")
public class AstroController {

    private final AstroModeService astroModeService;
    private final TaskExecutor taskExecutor;

    public AstroController(AstroModeService astroModeService,
                           @Qualifier("sessionTaskExecutor") TaskExecutor taskExecutor) {
        this.astroModeService = astroModeService;
        this.taskExecutor = taskExecutor;
    }

    /**
     * Request body for creating a new astro session.
     */
    public record CreateAstroSessionRequest(
            @Valid AstroCaptureConfig captureConfig,
            @Valid AstroFocusConfig focusConfig
    ) {}

    /**
     * Creates a new astro session and starts it asynchronously via the bounded session executor.
     */
    @PostMapping("/sessions")
    @Operation(summary = "Create and start astro session")
    public ResponseEntity<AstroSession> createSession(@Valid @RequestBody(required = false) CreateAstroSessionRequest request) {
        AstroCaptureConfig captureConfig = (request != null && request.captureConfig() != null)
                ? request.captureConfig() : AstroCaptureConfig.defaults();
        AstroFocusConfig focusConfig = (request != null && request.focusConfig() != null)
                ? request.focusConfig() : AstroFocusConfig.manualFixed();

        AstroSession session = astroModeService.createSession(captureConfig, focusConfig);
        // Start execution on bounded session executor
        taskExecutor.execute(() -> astroModeService.executeSession(session.id()));

        return ResponseEntity.created(URI.create("/api/astro/sessions/" + session.id()))
                .body(session);
    }

    /**
     * Gets a session by ID.
     */
    @GetMapping("/sessions/{id}")
    @Operation(summary = "Get astro session by id")
    public ResponseEntity<AstroSession> getSession(@PathVariable String id) {
        return astroModeService.getSession(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Lists all sessions.
     */
    @GetMapping("/sessions")
    @Operation(summary = "List astro sessions")
    public ResponseEntity<List<AstroSession>> listSessions() {
        return ResponseEntity.ok(astroModeService.listSessions());
    }

    /**
     * Aborts a running session.
     */
    @PostMapping("/sessions/{id}/abort")
    @Operation(summary = "Abort running astro session")
    public ResponseEntity<Map<String, String>> abortSession(@PathVariable String id) {
        astroModeService.requestAbort(id);
        return ResponseEntity.ok(Map.of("message", "Abort requested for session " + id));
    }

    /**
     * Deletes a terminal session and its files on disk.
     */
    @DeleteMapping("/sessions/{id}")
    @Operation(summary = "Delete terminal astro session")
    public ResponseEntity<Void> deleteSession(@PathVariable String id) {
        if (astroModeService.deleteSession(id)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * Returns the result info for a completed session.
     */
    @GetMapping("/sessions/{id}/result")
    @Operation(summary = "Get completed astro session result")
    public ResponseEntity<AstroSession> getResult(@PathVariable String id) {
        return astroModeService.getSession(id)
                .filter(s -> s.resultImagePath() != null)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
