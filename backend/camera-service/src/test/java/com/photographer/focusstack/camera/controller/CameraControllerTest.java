package com.photographer.focusstack.camera.controller;

import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.camera.CameraState;
import com.photographer.focusstack.common.camera.CaptureResult;
import com.photographer.focusstack.common.model.CameraProfile;
import com.photographer.focusstack.camera.search.SearchService;
import com.photographer.focusstack.camera.service.CameraProfileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CameraController.class)
class CameraControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CameraProfileService profileService;

    @MockitoBean
    private CameraBackend cameraBackend;

    @MockitoBean
    private SearchService searchService;

    @Test
    void listProfiles_returnsOk() throws Exception {
        when(profileService.getAllProfiles()).thenReturn(List.of());
        mockMvc.perform(get("/api/cameras"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void createProfile_returnsCreated() throws Exception {
        CameraProfile profile = new CameraProfile(
                "cam-1", "Test Cam", "mock", "localhost", 10, 30, 0.1);
        when(profileService.createProfile(any())).thenReturn(profile);

        mockMvc.perform(post("/api/cameras")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(profile)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("cam-1"));
    }

    @Test
    void getProfile_returnsProfile() throws Exception {
        CameraProfile profile = new CameraProfile(
                "cam-1", "Test Cam", "mock", "localhost", 10, 30, 0.1);
        when(profileService.getProfile("cam-1")).thenReturn(profile);

        mockMvc.perform(get("/api/cameras/cam-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Test Cam"));
    }

    @Test
    void getCameraStatus_returnsState() throws Exception {
        CameraProfile profile = new CameraProfile(
                "cam-1", "Test Cam", "mock", "localhost", 10, 30, 0.1);
        when(profileService.getProfile("cam-1")).thenReturn(profile);
        when(cameraBackend.getState()).thenReturn(
                new CameraState(true, 85, 10_000_000L, 0, "1/125", "f/8.0", "ISO 100", true));

        mockMvc.perform(get("/api/cameras/cam-1/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(true));
    }

    @Test
    void capture_returnsResult() throws Exception {
        CameraProfile profile = new CameraProfile(
                "cam-1", "Test Cam", "mock", "localhost", 10, 30, 0.1);
        when(profileService.getProfile("cam-1")).thenReturn(profile);
        when(cameraBackend.capture()).thenReturn(
                new CaptureResult("img-1", "IMG_0.jpg", "/mock/captures/img-1.jpg",
                        Instant.now(), 0, 1024L));

        mockMvc.perform(post("/api/cameras/cam-1/capture"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageId").value("img-1"));
    }
}
