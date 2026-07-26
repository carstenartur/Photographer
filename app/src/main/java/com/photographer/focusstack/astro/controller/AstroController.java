package com.photographer.focusstack.astro.controller;

import com.photographer.focusstack.app.api.ApiPaths;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({ApiPaths.V1 + "/astro", ApiPaths.LEGACY + "/astro"})
@Tag(name = "Astro", description = "Astro capture session lifecycle endpoints.")
public class AstroController {

    private final AstroModeService astroModeService;
    private final TaskExecutor taskExecutor;

    public AstroController(
            AstroModeService astroModeService,
            @Qualifier("sessionTaskExecutor") TaskExecutor taskExecutor) {
        this.astroModeService = astroModeService;
        this.taskExecutor = taskExecutor;
    }

    public record CreateAstroSessionRequest(
            @Valid AstroCaptureConfig captureConfig,
            @Valid AstroFocusConfig focusConfig
    ) {}

    @PostMapping("/sessions")
    @Operation(summary = "Create and start astro session")
    public ResponseEntity<AstroSession> createSession(
            @Valid @RequestBody(required = false) CreateAstroSessionRequest request) {
        AstroCaptureConfig captureConfig = request != null && request.captureConfig() != null
                ? request.captureConfig()
                : AstroCaptureConfig.defaults();
        AstroFocusConfig focusConfig = request != null && request.focusConfig() != null
                ? request.focusConfig()
                : AstroFocusConfig.manualFixed();

        AstroSession created = astroModeService.createSession(captureConfig, focusConfig);
        AstroModeService.PreparedExecution execution =
                astroModeService.prepareExecution(created.id());
        try {
            taskExecutor.execute(execution);
        } catch (RuntimeException schedulingFailure) {
            execution.reject(schedulingFailure);
            throw schedulingFailure;
        }

        AstroSession preparing = astroModeService.getSession(created.id())
                .orElseThrow(() -> new IllegalStateException("Created astro session is unavailable"));
        return ResponseEntity.created(
                        URI.create(ApiPaths.V1 + "/astro/sessions/" + created.id()))
                .body(preparing);
    }

    @GetMapping("/sessions/{id}")
    @Operation(summary = "Get astro session by id")
    public ResponseEntity<AstroSession> getSession(@PathVariable String id) {
        return astroModeService.getSession(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/sessions")
    @Operation(summary = "List astro sessions")
    public ResponseEntity<List<AstroSession>> listSessions() {
        return ResponseEntity.ok(astroModeService.listSessions());
    }

    @PostMapping("/sessions/{id}/abort")
    @Operation(summary = "Abort running astro session")
    public ResponseEntity<Map<String, String>> abortSession(@PathVariable String id) {
        astroModeService.requestAbort(id);
        return ResponseEntity.ok(Map.of("message", "Abort requested"));
    }

    @DeleteMapping("/sessions/{id}")
    @Operation(summary = "Delete terminal astro session")
    public ResponseEntity<Void> deleteSession(@PathVariable String id) {
        if (astroModeService.deleteSession(id)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    @GetMapping("/sessions/{id}/result")
    @Operation(summary = "Get completed astro session result")
    public ResponseEntity<AstroSession> getResult(@PathVariable String id) {
        return astroModeService.getSession(id)
                .filter(session -> session.resultImagePath() != null)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
