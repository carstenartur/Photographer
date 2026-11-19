package com.photographer.focusstack.observer.controller;

import com.photographer.focusstack.app.api.ApiPaths;
import com.photographer.focusstack.observer.bird.BirdTriggerSettings;
import com.photographer.focusstack.observer.service.LocalBirdObserverService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

/** Explicit preview/arm lifecycle for the local bird trigger; normal application authentication applies. */
@RestController
@RequestMapping({ApiPaths.V1 + "/observer/live", ApiPaths.LEGACY + "/observer/live"})
@Tag(name = "Local bird observer", description = "Local live-view bird detection, explicit arming, capture and stop lifecycle.")
public class LocalBirdObserverController {
    private final LocalBirdObserverService service;

    public LocalBirdObserverController(LocalBirdObserverService service) { this.service = service; }

    public record Request(String cameraId, boolean armed, BirdTriggerSettings settings) {}

    @GetMapping
    @Operation(summary = "Get local bird detection status and matching analysed preview")
    public LocalBirdObserverService.Status status() { return service.status(); }

    @PostMapping("/start")
    @Operation(summary = "Start local bird detection in preview-only mode")
    public LocalBirdObserverService.Status start(@RequestBody Request request) {
        return service.start(request.cameraId(), request.armed(), request.settings());
    }

    @PutMapping("/settings")
    @Operation(summary = "Update observation region and explicitly arm or disarm automatic capture")
    public LocalBirdObserverService.Status settings(@RequestBody Request request) {
        return service.update(request.cameraId(), request.armed(), request.settings());
    }

    @PostMapping("/stop")
    @Operation(summary = "Stop bird detection and drain any already committed capture")
    public LocalBirdObserverService.Status stop() { return service.stop(); }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail conflict(IllegalStateException error) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, error.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalid(IllegalArgumentException error) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, error.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail invalidBody(HttpMessageNotReadableException ignored) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "A valid observer request and in-frame region are required");
    }
}
