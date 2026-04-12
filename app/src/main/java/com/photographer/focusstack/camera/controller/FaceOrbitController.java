package com.photographer.focusstack.camera.controller;

import com.photographer.focusstack.common.model.CaptureSession;
import com.photographer.focusstack.common.model.FaceOrbitSettings;
import com.photographer.focusstack.common.model.FaceProfileJob;
import com.photographer.focusstack.common.model.QualityCheck;
import com.photographer.focusstack.session.FaceOrbitService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST API for Face Orbit Capture sessions.
 * Delegates all business logic to {@link FaceOrbitService}.
 */
@RestController
@RequestMapping("/api/face-orbit")
public class FaceOrbitController {

    private final FaceOrbitService service;
    private final TaskExecutor taskExecutor;

    public FaceOrbitController(FaceOrbitService service,
                                @Qualifier("sessionTaskExecutor") TaskExecutor taskExecutor) {
        this.service = service;
        this.taskExecutor = taskExecutor;
    }

    @PostMapping
    public ResponseEntity<CaptureSession> createSession(@Valid @RequestBody FaceOrbitSettings settings) {
        CaptureSession session = service.createSession(settings);
        return ResponseEntity.status(HttpStatus.CREATED).body(session);
    }

    @PostMapping("/{sessionId}/capture")
    public ResponseEntity<QualityCheck> captureNextStep(@PathVariable String sessionId) {
        QualityCheck quality = service.captureNextStep(sessionId);
        return ResponseEntity.ok(quality);
    }

    @PostMapping("/{sessionId}/auto")
    public ResponseEntity<CaptureSession> startAutoMode(@PathVariable String sessionId) {
        // Validate state synchronously before scheduling async execution
        service.validateAutoModeStartable(sessionId);
        taskExecutor.execute(() -> service.executeAutoMode(sessionId));
        CaptureSession session = service.getSession(sessionId);
        return ResponseEntity.accepted().body(session);
    }

    @PostMapping("/{sessionId}/stop")
    public ResponseEntity<Void> stopSession(@PathVariable String sessionId) {
        service.stopSession(sessionId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{sessionId}/complete")
    public ResponseEntity<CaptureSession> completeSession(@PathVariable String sessionId) {
        return ResponseEntity.ok(service.completeSession(sessionId));
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<CaptureSession> getSession(@PathVariable String sessionId) {
        return ResponseEntity.ok(service.getSession(sessionId));
    }

    @GetMapping("/{sessionId}/quality")
    public ResponseEntity<List<QualityCheck>> getQualityChecks(@PathVariable String sessionId) {
        return ResponseEntity.ok(service.getQualityChecks(sessionId));
    }

    @PostMapping("/{sessionId}/profile")
    public ResponseEntity<FaceProfileJob> generateProfile(@PathVariable String sessionId) {
        FaceProfileJob job = service.generateProfile(sessionId);
        return ResponseEntity.ok(job);
    }

    @GetMapping("/profile/{jobId}")
    public ResponseEntity<FaceProfileJob> getProfileJob(@PathVariable String jobId) {
        return ResponseEntity.ok(service.getProfileJob(jobId));
    }
}
