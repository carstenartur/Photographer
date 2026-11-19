package com.photographer.focusstack.app.controller;

import com.photographer.focusstack.app.security.UserRepository;
import com.photographer.focusstack.camera.backend.CameraBackendFactory;
import com.photographer.focusstack.camera.service.CameraProfileService;
import com.photographer.focusstack.camera.service.DirectCameraControlExecutor;
import com.photographer.focusstack.camera.session.CameraLeaseIdentityProvider;
import com.photographer.focusstack.camera.session.CameraLeaseRequest;
import com.photographer.focusstack.camera.session.CameraSessionLease;
import com.photographer.focusstack.camera.session.CameraSessionLeaseManager;
import com.photographer.focusstack.camera.session.CameraWorkflowType;
import com.photographer.focusstack.camera.session.DefaultCameraLeaseIdentityProvider;
import com.photographer.focusstack.camera.session.InMemoryCameraSessionLeaseManager;
import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.camera.CameraInfo;
import com.photographer.focusstack.common.model.CameraProfile;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SystemInfoController.class)
@WithMockUser
@Import({DirectCameraControlExecutor.class, DefaultCameraLeaseIdentityProvider.class,
        InMemoryCameraSessionLeaseManager.class})
class SystemInfoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CameraSessionLeaseManager leaseManager;

    @Autowired
    private CameraLeaseIdentityProvider identities;

    @MockitoBean
    private CameraProfileService profiles;

    @MockitoBean
    private CameraBackendFactory backendFactory;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    void reportsReservedCameraAsBusyWhileStillProbingAvailableCamera() throws Exception {
        CameraProfile reserved = new CameraProfile("reserved", "Reserved camera", "canon-ccapi",
                "reserved.example", 10, 30, 0.1);
        CameraProfile available = new CameraProfile("available", "Available camera", "canon-ccapi",
                "available.example", 10, 30, 0.1);
        when(profiles.getAllProfiles()).thenReturn(List.of(reserved, available));
        when(profiles.getProfile(reserved.id())).thenReturn(reserved);
        when(profiles.getProfile(available.id())).thenReturn(available);

        CameraBackend backend = mock(CameraBackend.class);
        when(backendFactory.getBackend(available)).thenReturn(backend);
        when(backend.getInfo()).thenReturn(new CameraInfo(
                "camera", "Available camera", "EOS", "canon-ccapi", "available.example", "1.0.0"));

        try (CameraSessionLease ignored = leaseManager.acquire(new CameraLeaseRequest(
                identities.resolve(reserved).orElseThrow(), CameraWorkflowType.WILDLIFE_OBSERVER,
                "bird-run", "bird-owner", Duration.ofMinutes(15)))) {
            mockMvc.perform(get("/api/v1/system/info"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.memory").exists())
                    .andExpect(jsonPath("$.cameras[0].id").value("reserved"))
                    .andExpect(jsonPath("$.cameras[0].busy").value(true))
                    .andExpect(jsonPath("$.cameras[1].id").value("available"))
                    .andExpect(jsonPath("$.cameras[1].connected").value(true));
            verify(backendFactory, never()).getBackend(reserved);
        }
    }
}
