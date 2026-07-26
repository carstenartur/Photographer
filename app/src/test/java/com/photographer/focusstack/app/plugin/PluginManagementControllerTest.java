package com.photographer.focusstack.app.plugin;

import com.photographer.focusstack.app.security.SecurityConfig;
import com.photographer.focusstack.app.security.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PluginManagementController.class)
@Import(SecurityConfig.class)
@WithMockUser(roles = "ADMIN")
class PluginManagementControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PluginManagementService pluginService;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    void canonicalV1RouteUsesTheSameAdminController() throws Exception {
        when(pluginService.listPlugins()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/admin/plugins"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    @WithMockUser(roles = "USER")
    void canonicalV1AdminRouteRejectsNonAdminUser() throws Exception {
        mockMvc.perform(get("/api/v1/admin/plugins"))
                .andExpect(status().isForbidden());
    }

    @Test
    void listPlugins_returnsEmptyList() throws Exception {
        when(pluginService.listPlugins()).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/plugins"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void listPlugins_returnsPluginInfo() throws Exception {
        Map<String, Object> pluginInfo = Map.of(
                "id", "test-plugin",
                "version", "1.0.0",
                "description", "A test plugin",
                "provider", "Test Author",
                "state", "STARTED",
                "license", "Apache-2.0");
        when(pluginService.listPlugins()).thenReturn(List.of(pluginInfo));

        mockMvc.perform(get("/api/admin/plugins"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("test-plugin"))
                .andExpect(jsonPath("$[0].state").value("STARTED"));
    }

    @Test
    void getPlugin_returnsNotFoundForUnknownId() throws Exception {
        when(pluginService.getPlugin("unknown")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/admin/plugins/unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getPlugin_returnsPluginDetails() throws Exception {
        Map<String, Object> pluginInfo = Map.of(
                "id", "my-plugin",
                "version", "2.0.0",
                "description", "My plugin",
                "provider", "",
                "state", "STARTED",
                "license", "");
        when(pluginService.getPlugin("my-plugin")).thenReturn(Optional.of(pluginInfo));

        mockMvc.perform(get("/api/admin/plugins/my-plugin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("my-plugin"))
                .andExpect(jsonPath("$.version").value("2.0.0"));
    }

    @Test
    void installPlugin_returnsPluginId() throws Exception {
        when(pluginService.installPlugin(eq("test.jar"), any())).thenReturn("test-plugin");

        MockMultipartFile file = new MockMultipartFile(
                "file", "test.jar", "application/java-archive", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/admin/plugins/install").file(file).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pluginId").value("test-plugin"))
                .andExpect(jsonPath("$.status").value("installed"));
    }

    @Test
    void installPlugin_returnsBadRequestForInvalidFile() throws Exception {
        when(pluginService.installPlugin(any(), any()))
                .thenThrow(new IllegalArgumentException("Only .jar plugin files are accepted"));

        MockMultipartFile file = new MockMultipartFile(
                "file", "bad.exe", "application/octet-stream", new byte[]{1, 2});

        mockMvc.perform(multipart("/api/admin/plugins/install").file(file).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Only .jar plugin files are accepted"));
    }

    @Test
    void installPlugin_returnsBadRequestForNullFilename() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", null, "application/java-archive", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/admin/plugins/install").file(file).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("File name must not be empty"));
    }

    @Test
    void enablePlugin_returnsOkOnSuccess() throws Exception {
        when(pluginService.enablePlugin("my-plugin")).thenReturn(true);

        mockMvc.perform(post("/api/admin/plugins/my-plugin/enable").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("enabled"));
    }

    @Test
    void enablePlugin_returnsNotFoundForUnknown() throws Exception {
        when(pluginService.enablePlugin("unknown")).thenReturn(false);

        mockMvc.perform(post("/api/admin/plugins/unknown/enable").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void disablePlugin_returnsOkOnSuccess() throws Exception {
        when(pluginService.disablePlugin("my-plugin")).thenReturn(true);

        mockMvc.perform(post("/api/admin/plugins/my-plugin/disable").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("disabled"));
    }

    @Test
    void uninstallPlugin_returnsOkOnSuccess() throws Exception {
        when(pluginService.uninstallPlugin("my-plugin")).thenReturn(true);

        mockMvc.perform(delete("/api/admin/plugins/my-plugin").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("uninstalled"));
    }

    @Test
    void uninstallPlugin_returnsNotFoundForUnknown() throws Exception {
        when(pluginService.uninstallPlugin("unknown")).thenReturn(false);

        mockMvc.perform(delete("/api/admin/plugins/unknown").with(csrf()))
                .andExpect(status().isNotFound());
    }
}
