package com.photographer.focusstack.camera.controller;

import com.photographer.focusstack.app.security.UserRepository;
import com.photographer.focusstack.camera.backend.CameraBackendFactory;
import com.photographer.focusstack.camera.search.SearchService;
import com.photographer.focusstack.camera.service.CameraProfileService;
import com.photographer.focusstack.camera.service.DirectCameraControlExecutor;
import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.camera.CameraInfo;
import com.photographer.focusstack.common.camera.CameraState;
import com.photographer.focusstack.common.camera.CaptureResult;
import com.photographer.focusstack.common.camera.FocusCommand;
import com.photographer.focusstack.common.model.CameraProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CameraController.class)
@WithMockUser
class CameraControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CameraProfileService profileService;

    @MockitoBean
    private CameraBackendFactory backendFactory;

    @MockitoBean
    private SearchService searchService;

    @MockitoBean
    private DirectCameraControlExecutor directControl;

    @MockitoBean
    private UserRepository userRepository;

    private CameraBackend cameraBackend;

    private static final CameraProfile PROFILE =
            new CameraProfile("cam-1", "Test Cam", "mock", "localhost", 10, 30, 0.1);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        cameraBackend = mock(CameraBackend.class);
        when(profileService.getProfile("cam-1")).thenReturn(PROFILE);
        when(backendFactory.getBackend(PROFILE)).thenReturn(cameraBackend);

        when(directControl.withBackend(eq("cam-1"), any(Function.class)))
                .thenAnswer(invocation -> ((Function<CameraBackend, Object>) invocation
                        .getArgument(1)).apply(cameraBackend));
        doAnswer(invocation -> {
            ((Consumer<CameraBackend>) invocation.getArgument(1)).accept(cameraBackend);
            return null;
        }).when(directControl).withBackendVoid(eq("cam-1"), any(Consumer.class));
        when(directControl.withProfileLease(eq("cam-1"), any(Supplier.class)))
                .thenAnswer(invocation -> ((Supplier<Object>) invocation
                        .getArgument(1)).get());
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(directControl).withProfileLeaseVoid(eq("cam-1"), any(Runnable.class));
    }

    @Test
    void canonicalAndLegacyListRoutesReturnEquivalentPayloads() throws Exception {
        when(profileService.getAllProfiles()).thenReturn(List.of(PROFILE));

        String canonical = mockMvc.perform(get("/api/v1/cameras"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String legacy = mockMvc.perform(get("/api/cameras"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertEquals(canonical, legacy);
    }

    @Test
    void listProfiles_returnsOk() throws Exception {
        when(profileService.getAllProfiles()).thenReturn(List.of());
        mockMvc.perform(get("/api/cameras"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void createProfile_returnsCreated() throws Exception {
        when(profileService.createProfile(any())).thenReturn(PROFILE);

        mockMvc.perform(post("/api/cameras")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(PROFILE)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("cam-1"));
    }

    @Test
    void getProfile_returnsProfile() throws Exception {
        mockMvc.perform(get("/api/cameras/cam-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Test Cam"));
    }

    @Test
    void getCameraInfo_returnsInfo() throws Exception {
        when(cameraBackend.getInfo()).thenReturn(
                new CameraInfo("mock-001", "Mock Camera", "Mock Camera", "mock", "localhost", "1.0.0"));

        mockMvc.perform(get("/api/cameras/cam-1/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("Mock Camera"))
                .andExpect(jsonPath("$.firmwareVersion").value("1.0.0"));
    }

    @Test
    void getCameraStatus_returnsState() throws Exception {
        when(cameraBackend.getState()).thenReturn(
                new CameraState(true, 85, 10_000_000L, 0, "1/125", "f/8.0", "ISO 100", true));

        mockMvc.perform(get("/api/cameras/cam-1/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(true));
    }

    @Test
    void capture_runsThroughDirectControlExecutor() throws Exception {
        when(cameraBackend.capture()).thenReturn(
                new CaptureResult("img-1", "IMG_0.jpg", "/mock/captures/img-1.jpg",
                        Instant.now(), 0, 1024L, null));

        mockMvc.perform(post("/api/cameras/cam-1/capture")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageId").value("img-1"));

        verify(directControl).withBackend(eq("cam-1"), any(Function.class));
    }

    @Test
    void focus_runsThroughDirectControlExecutor() throws Exception {
        FocusCommand command = new FocusCommand(FocusCommand.Direction.FAR, 3);

        mockMvc.perform(post("/api/cameras/cam-1/focus")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(command)))
                .andExpect(status().isOk());

        verify(directControl).withBackendVoid(eq("cam-1"), any(Consumer.class));
        verify(cameraBackend).setFocus(command);
    }

    @Test
    void updateProfile_runsEvictionAndMutationInsideProfileLease() throws Exception {
        when(profileService.updateProfile("cam-1", PROFILE)).thenReturn(PROFILE);

        mockMvc.perform(put("/api/cameras/cam-1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(PROFILE)))
                .andExpect(status().isOk());

        verify(directControl).withProfileLease(eq("cam-1"), any(Supplier.class));
        verify(backendFactory).evict("cam-1");
        verify(profileService).updateProfile("cam-1", PROFILE);
    }

    @Test
    void deleteProfile_runsEvictionAndDeletionInsideProfileLease() throws Exception {
        mockMvc.perform(delete("/api/cameras/cam-1")
                        .with(csrf()))
                .andExpect(status().isNoContent());

        verify(directControl).withProfileLeaseVoid(eq("cam-1"), any(Runnable.class));
        verify(backendFactory).evict("cam-1");
        verify(profileService).deleteProfile("cam-1");
    }
}
