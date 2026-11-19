package com.photographer.focusstack.camera.controller;

import com.photographer.focusstack.app.security.UserRepository;
import com.photographer.focusstack.camera.backend.CameraBackendFactory;
import com.photographer.focusstack.camera.search.SearchService;
import com.photographer.focusstack.camera.service.CameraProfileService;
import com.photographer.focusstack.camera.service.DirectCameraControlExecutor;
import com.photographer.focusstack.camera.session.DefaultCameraLeaseIdentityProvider;
import com.photographer.focusstack.camera.session.InMemoryCameraSessionLeaseManager;
import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.camera.CameraCapabilityProvider;
import com.photographer.focusstack.common.camera.CameraCapabilitySnapshot;
import com.photographer.focusstack.common.camera.CameraInfo;
import com.photographer.focusstack.common.model.CameraProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;

import static com.photographer.focusstack.common.camera.CameraCapabilitySnapshot.Availability.AVAILABLE;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CameraController.class)
@WithMockUser
@Import({DirectCameraControlExecutor.class, DefaultCameraLeaseIdentityProvider.class,
        InMemoryCameraSessionLeaseManager.class})
class CameraCapabilityControllerTest {

    private static final CameraProfile PROFILE =
            new CameraProfile("cam-1", "Test Cam", "canon-ccapi",
                    "http://secret-camera:8080", 10, 30, 0.1);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CameraProfileService profileService;

    @MockitoBean
    private CameraBackendFactory backendFactory;

    @MockitoBean
    private SearchService searchService;

    @MockitoBean
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        when(profileService.getProfile("cam-1")).thenReturn(PROFILE);
    }

    @Test
    void exposesProviderSnapshotWithoutTransportAddress() throws Exception {
        CameraBackend backend = mock(CameraBackend.class,
                withSettings().extraInterfaces(CameraCapabilityProvider.class));
        CameraCapabilitySnapshot snapshot = new CameraCapabilitySnapshot(
                "EOS R7",
                "1.6.0",
                true,
                List.of(new CameraCapabilitySnapshot.Capability(
                        "lens-status", true, true, AVAILABLE,
                        Set.of("GET"), Set.of("GET"), true, null)),
                List.of(new CameraCapabilitySnapshot.Endpoint(
                        "ver100", "/ccapi/ver100/devicestatus/lens",
                        Set.of("GET"), true)),
                new CameraCapabilitySnapshot.Lens(true, "RF100mm"));
        when(((CameraCapabilityProvider) backend).getCapabilitySnapshot()).thenReturn(snapshot);
        when(backendFactory.getBackend(PROFILE)).thenReturn(backend);

        mockMvc.perform(get("/api/cameras/cam-1/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("EOS R7"))
                .andExpect(jsonPath("$.capabilities[0].availability").value("AVAILABLE"))
                .andExpect(jsonPath("$.advertisedEndpoints[0].path")
                        .value("/ccapi/ver100/devicestatus/lens"))
                .andExpect(jsonPath("$.advertisedEndpoints[0].url").doesNotExist())
                .andExpect(jsonPath("$.host").doesNotExist())
                .andExpect(jsonPath("$.lens.name").value("RF100mm"));
    }

    @Test
    void reportsDiagnosticsUnavailableWithoutCopyingLegacyHost() throws Exception {
        CameraBackend backend = mock(CameraBackend.class);
        when(backend.getInfo()).thenReturn(new CameraInfo(
                "camera-serial-derived-id",
                "Camera",
                "Folder Camera",
                "folder",
                "/private/capture/path",
                "unknown"));
        when(backendFactory.getBackend(PROFILE)).thenReturn(backend);

        mockMvc.perform(get("/api/cameras/cam-1/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.catalogAvailable").value(false))
                .andExpect(jsonPath("$.model").value("Folder Camera"))
                .andExpect(jsonPath("$.advertisedEndpoints").isEmpty())
                .andExpect(jsonPath("$.host").doesNotExist())
                .andExpect(jsonPath("$.id").doesNotExist());
    }
}
