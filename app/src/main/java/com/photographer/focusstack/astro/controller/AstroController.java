package com.photographer.focusstack.astro.controller;

import com.photographer.focusstack.astro.service.AstroModeService;
import com.photographer.focusstack.common.model.AstroCaptureConfig;
import com.photographer.focusstack.common.model.AstroFocusConfig;
import com.photographer.focusstack.common.model.AstroSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/astro")
public class AstroController {

    private final AstroModeService astroModeService;

    public AstroController(AstroModeService astroModeService) {
        this.astroModeService = astroModeService;
    }

    /**
     * Request body for creating a new astro session.
     */
    public record CreateAstroSessionRequest(
            AstroCaptureConfig captureConfig,
            AstroFocusConfig focusConfig
    ) {}

    /**
     * Creates a new astro session and starts it asynchronously.
     */
    @PostMapping("/sessions")
    public ResponseEntity<AstroSession> createSession(@RequestBody(required = false) CreateAstroSessionRequest request) {
        AstroCaptureConfig captureConfig = (request != null && request.captureConfig() != null)
                ? request.captureConfig() : AstroCaptureConfig.defaults();
        AstroFocusConfig focusConfig = (request != null && request.focusConfig() != null)
                ? request.focusConfig() : AstroFocusConfig.manualFixed();

        AstroSession session = astroModeService.createSession(captureConfig, focusConfig);
        // Start execution asynchronously
        astroModeService.executeSession(session.id());

        return ResponseEntity.created(URI.create("/api/astro/sessions/" + session.id()))
                .body(session);
    }

    /**
     * Gets a session by ID.
     */
    @GetMapping("/sessions/{id}")
    public ResponseEntity<AstroSession> getSession(@PathVariable String id) {
        return astroModeService.getSession(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Lists all sessions.
     */
    @GetMapping("/sessions")
    public ResponseEntity<List<AstroSession>> listSessions() {
        return ResponseEntity.ok(astroModeService.listSessions());
    }

    /**
     * Aborts a running session.
     */
    @PostMapping("/sessions/{id}/abort")
    public ResponseEntity<Map<String, String>> abortSession(@PathVariable String id) {
        astroModeService.requestAbort(id);
        return ResponseEntity.ok(Map.of("message", "Abort requested for session " + id));
    }

    /**
     * Returns the result info for a completed session.
     */
    @GetMapping("/sessions/{id}/result")
    public ResponseEntity<AstroSession> getResult(@PathVariable String id) {
        return astroModeService.getSession(id)
                .filter(s -> s.resultImagePath() != null)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
