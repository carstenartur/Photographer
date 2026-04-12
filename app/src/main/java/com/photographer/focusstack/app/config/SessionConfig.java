package com.photographer.focusstack.app.config;

import com.photographer.focusstack.capture.BracketCaptureService;
import com.photographer.focusstack.capture.SequenceCaptureService;
import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.face.analysis.FaceAnalysisEngine;
import com.photographer.focusstack.face.analysis.MockFaceAnalysisEngine;
import com.photographer.focusstack.face.profile.FaceProfileEngine;
import com.photographer.focusstack.face.profile.MockFaceProfileEngine;
import com.photographer.focusstack.hdr.HdrProcessor;
import com.photographer.focusstack.hdr.MockHdrProcessor;
import com.photographer.focusstack.hdr.MockTonemapper;
import com.photographer.focusstack.hdr.Tonemapper;
import com.photographer.focusstack.session.FaceOrbitService;
import com.photographer.focusstack.session.HdrTimelapseService;
import com.photographer.focusstack.storage.FileSessionStorage;
import com.photographer.focusstack.storage.SessionStorage;
import com.photographer.focusstack.video.MockVideoEncoder;
import com.photographer.focusstack.video.VideoEncoder;
import com.photographer.focusstack.camera.backend.MockCameraBackend;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.nio.file.Path;

/**
 * Spring configuration for HDR Timelapse and Face Orbit session services.
 * Wires the core modules together using mock adapters by default.
 */
@Configuration
public class SessionConfig {

    @Bean
    public SessionStorage sessionStorage(
            @Value("${focusstack.sessions.base-dir:./data/sessions}") String baseDir) {
        return new FileSessionStorage(Path.of(baseDir));
    }

    @Bean
    public HdrProcessor hdrProcessor() {
        return new MockHdrProcessor();
    }

    @Bean
    public Tonemapper tonemapper() {
        return new MockTonemapper();
    }

    @Bean
    public VideoEncoder videoEncoder() {
        return new MockVideoEncoder();
    }

    @Bean
    public FaceAnalysisEngine faceAnalysisEngine() {
        return new MockFaceAnalysisEngine();
    }

    @Bean
    public FaceProfileEngine faceProfileEngine() {
        return new MockFaceProfileEngine();
    }

    @Bean
    public HdrTimelapseService hdrTimelapseService(
            SessionStorage sessionStorage,
            HdrProcessor hdrProcessor,
            Tonemapper tonemapper,
            VideoEncoder videoEncoder) {
        CameraBackend mockBackend = new MockCameraBackend(0);
        BracketCaptureService captureService = new BracketCaptureService(mockBackend);
        return new HdrTimelapseService(captureService, sessionStorage,
                hdrProcessor, tonemapper, videoEncoder);
    }

    @Bean
    public FaceOrbitService faceOrbitService(
            SessionStorage sessionStorage,
            FaceAnalysisEngine faceAnalysisEngine,
            FaceProfileEngine faceProfileEngine) {
        CameraBackend mockBackend = new MockCameraBackend(0);
        SequenceCaptureService captureService = new SequenceCaptureService(mockBackend);
        return new FaceOrbitService(captureService, sessionStorage,
                faceAnalysisEngine, faceProfileEngine);
    }

    /**
     * Dedicated thread pool for session tasks (HDR timelapse, face orbit auto mode).
     */
    @Bean("sessionTaskExecutor")
    public TaskExecutor sessionTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("session-task-");
        executor.initialize();
        return executor;
    }
}
