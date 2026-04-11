package com.photographer.focusstack.camera.controller;

import com.photographer.focusstack.app.security.UserRepository;
import com.photographer.focusstack.common.model.CaptureSession;
import com.photographer.focusstack.common.model.HdrSettings;
import com.photographer.focusstack.common.model.SessionStatus;
import com.photographer.focusstack.session.HdrTimelapseService;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HdrTimelapseController.class)
@WithMockUser
class HdrTimelapseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private HdrTimelapseService hdrTimelapseService;

    @MockitoBean(name = "sessionTaskExecutor")
    private TaskExecutor sessionTaskExecutor;

    @MockitoBean
    private UserRepository userRepository;

    private static final CaptureSession SAMPLE_SESSION = new CaptureSession(
            "sess-1", "hdr-timelapse", SessionStatus.CREATED,
            Instant.now(), null, null,
            List.of(), null, Map.of());

    @Test
    void createSession_returnsCreated() throws Exception {
        when(hdrTimelapseService.createSession(any())).thenReturn(SAMPLE_SESSION);

        HdrSettings settings = HdrSettings.defaults();
        mockMvc.perform(post("/api/hdr-timelapse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(settings)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sessionId").value("sess-1"))
                .andExpect(jsonPath("$.type").value("hdr-timelapse"));
    }

    @Test
    void getSession_returnsOk() throws Exception {
        when(hdrTimelapseService.getSession("sess-1")).thenReturn(SAMPLE_SESSION);

        mockMvc.perform(get("/api/hdr-timelapse/sess-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("sess-1"));
    }

    @Test
    void startSession_returnsAccepted() throws Exception {
        when(hdrTimelapseService.getSession("sess-1")).thenReturn(SAMPLE_SESSION);

        mockMvc.perform(post("/api/hdr-timelapse/sess-1/start"))
                .andExpect(status().isAccepted());
    }

    @Test
    void stopSession_returnsOk() throws Exception {
        mockMvc.perform(post("/api/hdr-timelapse/sess-1/stop"))
                .andExpect(status().isOk());

        verify(hdrTimelapseService).stopSession("sess-1");
    }

    @Test
    void getBrackets_returnsOk() throws Exception {
        when(hdrTimelapseService.getBrackets("sess-1")).thenReturn(List.of());

        mockMvc.perform(get("/api/hdr-timelapse/sess-1/brackets"))
                .andExpect(status().isOk());
    }
}
