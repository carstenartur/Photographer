package com.photographer.focusstack.app.config;

import com.photographer.focusstack.camera.backend.CameraBackendFactory;
import com.photographer.focusstack.camera.service.CameraProfileService;
import com.photographer.focusstack.camera.session.CameraLeaseIdentityProvider;
import com.photographer.focusstack.camera.session.CameraSessionLeaseManager;
import com.photographer.focusstack.camera.session.LeasedCameraBackendProvider;
import com.photographer.focusstack.face.analysis.FaceAnalysisEngine;
import com.photographer.focusstack.face.analysis.MockFaceAnalysisEngine;
import com.photographer.focusstack.face.profile.FaceProfileEngine;
import com.photographer.focusstack.face.profile.MockFaceProfileEngine;
import com.photographer.focusstack.hdr.HdrProcessor;
import com.photographer.focusstack.hdr.MockHdrProcessor;
import com.photographer.focusstack.hdr.MockTonemapper;
import com.photographer.focusstack.hdr.Tonemapper;
import com.photographer.focusstack.session.CameraBackendProvider;
import com.photographer.focusstack.session.CameraSessionPurpose;
import com.photographer.focusstack.session.FaceOrbitService;
import com.photographer.focusstack.session.HdrTimelapseService;
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
import java.time.Duration;
import java.util.Map;

/**
 * Spring configuration for HDR Timelapse and Face Orbit session services.
 * Camera-owning executions open a backend session whose logical ownership is
 * independent of the backend cache.
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
    public CameraBackendProvider cameraBackendProvider(
            CameraBackendFactory backendFactory,
            CameraProfileService profileService,
            CameraSessionLeaseManager leaseManager,
            CameraLeaseIdentityProvider identityProvider,
            @Value("${camera.session-lease.hdr-timelapse-lifetime:PT6H}")
            Duration hdrTimelapseLifetime,
            @Value("${camera.session-lease.face-orbit-lifetime:PT2H}")
            Duration faceOrbitLifetime,
            @Value("${camera.session-lease.astro-lifetime:PT6H}")
            Duration astroLifetime) {
        return new LeasedCameraBackendProvider(
                backendFactory,
                profileService,
                leaseManager,
                identityProvider,
                Map.of(
                        CameraSessionPurpose.HDR_TIMELAPSE, hdrTimelapseLifetime,
                        CameraSessionPurpose.FACE_ORBIT, faceOrbitLifetime,
                        CameraSessionPurpose.ASTRO, astroLifetime));
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
