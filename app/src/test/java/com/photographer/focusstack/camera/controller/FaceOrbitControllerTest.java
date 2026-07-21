package com.photographer.focusstack.camera.controller;

import com.photographer.focusstack.app.security.UserRepository;
import com.photographer.focusstack.common.model.CaptureSession;
import com.photographer.focusstack.common.model.FaceOrbitSettings;
import com.photographer.focusstack.common.model.FaceProfileJob;
import com.photographer.focusstack.common.model.QualityCheck;
import com.photographer.focusstack.common.model.SessionStatus;
import com.photographer.focusstack.session.FaceOrbitService;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FaceOrbitController.class)
@WithMockUser
class FaceOrbitControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private FaceOrbitService faceOrbitService;

    @MockitoBean(name = "sessionTaskExecutor")
    private TaskExecutor sessionTaskExecutor;

    @MockitoBean
    private UserRepository userRepository;

    private static final CaptureSession SAMPLE_SESSION = new CaptureSession(
            "sess-2", "face-orbit", SessionStatus.CREATED,
            Instant.now(), null, null,
            List.of(), null, Map.of());
    private static final CaptureSession RUNNING_SESSION = new CaptureSession(
            "sess-2", "face-orbit", SessionStatus.RUNNING,
            SAMPLE_SESSION.createdAt(), Instant.now(), null,
            List.of(), null, Map.of());

    @Test
    void createSession_returnsCreated() throws Exception {
        when(faceOrbitService.createSession(any())).thenReturn(SAMPLE_SESSION);

        FaceOrbitSettings settings = FaceOrbitSettings.defaults();
        mockMvc.perform(post("/api/face-orbit")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(settings)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sessionId").value("sess-2"))
                .andExpect(jsonPath("$.type").value("face-orbit"));
    }

    @Test
    void getSession_returnsOk() throws Exception {
        when(faceOrbitService.getSession("sess-2")).thenReturn(SAMPLE_SESSION);

        mockMvc.perform(get("/api/face-orbit/sess-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("sess-2"));
    }

    @Test
    void captureNextStep_returnsQualityCheck() throws Exception {
        QualityCheck quality = new QualityCheck("f1", 0.85, 0.90, true, 0.95, true, "OK");
        when(faceOrbitService.captureNextStep("sess-2")).thenReturn(quality);

        mockMvc.perform(post("/api/face-orbit/sess-2/capture")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(true));
    }

    @Test
    void startAutoMode_preparesOwnershipBeforeScheduling() throws Exception {
        FaceOrbitService.PreparedAutoExecution execution =
                mock(FaceOrbitService.PreparedAutoExecution.class);
        when(faceOrbitService.prepareAutoExecution("sess-2")).thenReturn(execution);
        when(faceOrbitService.getSession("sess-2")).thenReturn(RUNNING_SESSION);

        mockMvc.perform(post("/api/face-orbit/sess-2/auto")
                        .with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RUNNING"));

        verify(faceOrbitService).prepareAutoExecution("sess-2");
        verify(sessionTaskExecutor).execute(execution);
    }

    @Test
    void executorRejection_rejectsPreparedAutoExecution() throws Exception {
        FaceOrbitService.PreparedAutoExecution execution =
                mock(FaceOrbitService.PreparedAutoExecution.class);
        IllegalStateException rejection = new IllegalStateException("executor rejected");
        when(faceOrbitService.prepareAutoExecution("sess-2")).thenReturn(execution);
        doThrow(rejection).when(sessionTaskExecutor).execute(execution);

        mockMvc.perform(post("/api/face-orbit/sess-2/auto")
                        .with(csrf()))
                .andExpect(status().isBadRequest());

        verify(execution).reject(rejection);
    }

    @Test
    void stopSession_returnsOk() throws Exception {
        mockMvc.perform(post("/api/face-orbit/sess-2/stop")
                        .with(csrf()))
                .andExpect(status().isOk());

        verify(faceOrbitService).stopSession("sess-2");
    }

    @Test
    void completeSession_returnsOk() throws Exception {
        CaptureSession completed = new CaptureSession(
                "sess-2", "face-orbit", SessionStatus.COMPLETED,
                Instant.now(), Instant.now(), Instant.now(),
                List.of(), null, Map.of());
        when(faceOrbitService.completeSession("sess-2")).thenReturn(completed);

        mockMvc.perform(post("/api/face-orbit/sess-2/complete")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void generateProfile_returnsOk() throws Exception {
        FaceProfileJob job = new FaceProfileJob(
                "job-1", "sess-2", SessionStatus.COMPLETED,
                Instant.now(), Instant.now(), "results/profile.json", null, 300, 0.85);
        when(faceOrbitService.generateProfile("sess-2")).thenReturn(job);

        mockMvc.perform(post("/api/face-orbit/sess-2/profile")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("job-1"))
                .andExpect(jsonPath("$.vertexCount").value(300));
    }

    @Test
    void getQualityChecks_returnsOk() throws Exception {
        when(faceOrbitService.getQualityChecks("sess-2")).thenReturn(List.of());

        mockMvc.perform(get("/api/face-orbit/sess-2/quality"))
                .andExpect(status().isOk());
    }
}
