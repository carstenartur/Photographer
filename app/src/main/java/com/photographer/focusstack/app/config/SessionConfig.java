package com.photographer.focusstack.app.config;

import com.photographer.focusstack.camera.backend.CameraBackendFactory;
import com.photographer.focusstack.camera.service.CameraProfileService;
import com.photographer.focusstack.face.analysis.FaceAnalysisEngine;
import com.photographer.focusstack.face.analysis.MockFaceAnalysisEngine;
import com.photographer.focusstack.face.profile.FaceProfileEngine;
import com.photographer.focusstack.face.profile.MockFaceProfileEngine;
import com.photographer.focusstack.hdr.HdrProcessor;
import com.photographer.focusstack.hdr.MockHdrProcessor;
import com.photographer.focusstack.hdr.MockTonemapper;
import com.photographer.focusstack.hdr.Tonemapper;
import com.photographer.focusstack.session.CameraBackendProvider;
import com.photographer.focusstack.session.FaceOrbitService;
import com.photographer.focusstack.session.HdrTimelapseService;
import com.photographer.focusstack.session.SessionException;
import com.photographer.focusstack.storage.FileSessionStorage;
import com.photographer.focusstack.storage.SessionStorage;
import com.photographer.focusstack.video.MockVideoEncoder;
import com.photographer.focusstack.video.VideoEncoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.nio.file.Path;

/**
 * Spring configuration for HDR Timelapse and Face Orbit session services.
 * Wires the core modules together.  Camera backends are resolved at
 * session-start time via {@link CameraBackendFactory} using the camera profile
 * ID stored in the session settings.
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

    /**
     * {@link CameraBackendProvider} that resolves camera backends at session-start
     * time via {@link CameraBackendFactory}, just as {@code StackingJobService} does.
     */
    @Bean
    public CameraBackendProvider cameraBackendProvider(CameraBackendFactory backendFactory,
                                                        CameraProfileService profileService) {
        return cameraProfileId -> {
            try {
                return backendFactory.getBackend(profileService.getProfile(cameraProfileId));
            } catch (Exception ex) {
                throw new SessionException(
                        "Cannot resolve camera backend for profile '" + cameraProfileId + "': "
                        + ex.getMessage(), ex);
            }
        };
    }

    @Bean
    public HdrTimelapseService hdrTimelapseService(
            CameraBackendProvider cameraBackendProvider,
            SessionStorage sessionStorage,
            HdrProcessor hdrProcessor,
            Tonemapper tonemapper,
            VideoEncoder videoEncoder) {
        return new HdrTimelapseService(cameraBackendProvider, sessionStorage,
                hdrProcessor, tonemapper, videoEncoder);
    }

    @Bean
    public FaceOrbitService faceOrbitService(
            CameraBackendProvider cameraBackendProvider,
            SessionStorage sessionStorage,
            FaceAnalysisEngine faceAnalysisEngine,
            FaceProfileEngine faceProfileEngine) {
        return new FaceOrbitService(cameraBackendProvider, sessionStorage,
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
