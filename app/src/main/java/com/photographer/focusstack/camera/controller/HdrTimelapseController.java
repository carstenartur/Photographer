package com.photographer.focusstack.camera.controller;

import com.photographer.focusstack.common.model.BracketSet;
import com.photographer.focusstack.common.model.CaptureSession;
import com.photographer.focusstack.common.model.HdrSettings;
import com.photographer.focusstack.session.HdrTimelapseService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST API for HDR Timelapse sessions.
 * Delegates all business logic to {@link HdrTimelapseService}.
 */
@RestController
@RequestMapping("/api/hdr-timelapse")
public class HdrTimelapseController {

    private final HdrTimelapseService service;
    private final TaskExecutor taskExecutor;

    public HdrTimelapseController(HdrTimelapseService service,
                                   @Qualifier("sessionTaskExecutor") TaskExecutor taskExecutor) {
        this.service = service;
        this.taskExecutor = taskExecutor;
    }

    @PostMapping
    public ResponseEntity<CaptureSession> createSession(@Valid @RequestBody HdrSettings settings) {
        CaptureSession session = service.createSession(settings);
        return ResponseEntity.status(HttpStatus.CREATED).body(session);
    }

    @PostMapping("/{sessionId}/start")
    public ResponseEntity<CaptureSession> startSession(@PathVariable String sessionId) {
        // Start async execution
        taskExecutor.execute(() -> service.executeSession(sessionId));
        // Return current session state immediately
        CaptureSession session = service.getSession(sessionId);
        return ResponseEntity.accepted().body(session);
    }

    @PostMapping("/{sessionId}/stop")
    public ResponseEntity<Void> stopSession(@PathVariable String sessionId) {
        service.stopSession(sessionId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<CaptureSession> getSession(@PathVariable String sessionId) {
        return ResponseEntity.ok(service.getSession(sessionId));
    }

    @GetMapping("/{sessionId}/brackets")
    public ResponseEntity<List<BracketSet>> getBrackets(@PathVariable String sessionId) {
        return ResponseEntity.ok(service.getBrackets(sessionId));
    }

    @PostMapping("/{sessionId}/video")
    public ResponseEntity<byte[]> generateVideo(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "24") int fps,
            @RequestParam(defaultValue = "1920") int width,
            @RequestParam(defaultValue = "1080") int height) {
        byte[] video = service.generateVideo(sessionId, fps, width, height);
        if (video.length == 0) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"timelapse-" + sessionId + ".mp4\"")
                .body(video);
    }
}
