package com.photographer.focusstack.camera.controller;

import com.photographer.focusstack.app.security.UserRepository;
import com.photographer.focusstack.camera.discovery.CcapiDiscoveredCamera;
import com.photographer.focusstack.camera.discovery.CcapiDiscoveryResult;
import com.photographer.focusstack.camera.discovery.CcapiDiscoveryService;
import com.photographer.focusstack.camera.discovery.CcapiDiscoveryWarning;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CameraDiscoveryController.class)
@WithMockUser
class CameraDiscoveryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CcapiDiscoveryService discoveryService;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    void discoversWithBoundedDefaultAndReturnsStableHttpContract() throws Exception {
        CcapiDiscoveredCamera camera = new CcapiDiscoveredCamera(
                URI.create("http://127.0.0.1:49152/device.xml"),
                URI.create("http://127.0.0.1:8080/ccapi"),
                "uuid:camera-one",
                "Canon Camera",
                "PowerShot G5 X Mark II",
                "123456789",
                "Studio",
                true);
        when(discoveryService.discover(Duration.ofSeconds(5))).thenReturn(
                new CcapiDiscoveryResult(
                        List.of(camera),
                        List.of(new CcapiDiscoveryWarning(
                                "ssdp", "127.0.0.2", "Malformed response ignored"))));

        mockMvc.perform(post("/api/cameras/discovery").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cameras[0].accessUrl")
                        .value("http://127.0.0.1:8080/ccapi"))
                .andExpect(jsonPath("$.cameras[0].udn").value("uuid:camera-one"))
                .andExpect(jsonPath("$.cameras[0].modelName")
                        .value("PowerShot G5 X Mark II"))
                .andExpect(jsonPath("$.cameras[0].serviceConnected").value(true))
                .andExpect(jsonPath("$.cameras[0].descriptionUrl").doesNotExist())
                .andExpect(jsonPath("$.warnings[0].stage").value("ssdp"))
                .andExpect(jsonPath("$.warnings[0].source").value("127.0.0.2"));

        verify(discoveryService).discover(Duration.ofSeconds(5));
    }

    @Test
    void passesExplicitTimeoutToDiscoveryCore() throws Exception {
        when(discoveryService.discover(Duration.ofSeconds(12))).thenReturn(
                new CcapiDiscoveryResult(List.of(), List.of()));

        mockMvc.perform(post("/api/cameras/discovery")
                        .param("timeoutSeconds", "12")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cameras").isEmpty())
                .andExpect(jsonPath("$.warnings").isEmpty());

        verify(discoveryService).discover(Duration.ofSeconds(12));
        verify(discoveryService, never()).discover(Duration.ofSeconds(5));
    }

    @Test
    void rejectsNonPositiveTimeoutWithoutStartingNetworkDiscovery() throws Exception {
        mockMvc.perform(post("/api/cameras/discovery")
                        .param("timeoutSeconds", "0")
                        .with(csrf()))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(discoveryService);
    }

    @Test
    void rejectsTimeoutAboveHttpPolicyLimitWithoutStartingNetworkDiscovery() throws Exception {
        mockMvc.perform(post("/api/cameras/discovery")
                        .param("timeoutSeconds", "31")
                        .with(csrf()))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(discoveryService);
    }
}
