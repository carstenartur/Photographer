package com.photographer.focusstack.observer.controller;

import com.photographer.focusstack.app.security.UserRepository;
import com.photographer.focusstack.observer.bird.BirdTriggerSettings;
import com.photographer.focusstack.observer.service.LocalBirdObserverService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(LocalBirdObserverController.class)
class LocalBirdObserverControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean LocalBirdObserverService service;
    @MockitoBean UserRepository users;

    @Test
    void statusRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/observer/live")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser
    void exposesStatusAndAcceptsPreviewThenExplicitSettings() throws Exception {
        var stopped = new LocalBirdObserverService.Status(false, false, "STOPPED", null, "test", true, null,
                BirdTriggerSettings.defaults(), 0, 0, 0, null, null, null, null, null, null);
        when(service.status()).thenReturn(stopped);
        mvc.perform(get("/api/v1/observer/live")).andExpect(status().isOk())
                .andExpect(jsonPath("$.modelAvailable").value(true))
                .andExpect(jsonPath("$.settings.repeatSeconds").value(5));
        mvc.perform(post("/api/v1/observer/live/start").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"cameraId\":\"perch\",\"armed\":false}"))
                .andExpect(status().isOk());
        verify(service).start("perch", false, null);
        mvc.perform(post("/api/v1/observer/live/stop").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        verify(service).stop();
    }

    @Test
    @WithMockUser
    void rejectsInvalidRegionAndMissingBodyAsBadRequests() throws Exception {
        mvc.perform(put("/api/v1/observer/live/settings").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"cameraId":"perch","armed":true,"settings":{
                          "roi":{"x":0.9,"y":0,"width":0.2,"height":1,"label":"perch"},
                          "minConfidence":0.5,"minArea":0.0025,"intervalMs":500,"repeatSeconds":5,"absenceSeconds":3}}
                        """))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/observer/live/start").with(csrf()).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    @WithMockUser
    void runningConflictIsReviewableProblemDetail() throws Exception {
        when(service.start(anyString(), eq(false), any())).thenThrow(new IllegalStateException("Observer is already running"));
        mvc.perform(post("/api/v1/observer/live/start").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"cameraId\":\"perch\",\"armed\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Observer is already running"));
    }
}
