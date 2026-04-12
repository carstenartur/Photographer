package com.photographer.focusstack.observer.controller;

import com.photographer.focusstack.app.security.UserRepository;
import com.photographer.focusstack.observer.config.ObserverProperties;
import com.photographer.focusstack.observer.entity.ObservationEventEntity;
import com.photographer.focusstack.observer.entity.SpeciesRecordEntity;
import com.photographer.focusstack.observer.events.EventState;
import com.photographer.focusstack.observer.service.ObserverService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ObserverController.class)
@WithMockUser
class ObserverControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ObserverService observerService;

    @MockitoBean
    private ObserverProperties observerProperties;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    void getRecentEventsReturnsOk() throws Exception {
        ObservationEventEntity event = new ObservationEventEntity("evt-1");
        event.setTimestamp(Instant.now());
        event.setState(EventState.COMPLETED);
        event.setMode("wildlife");

        when(observerService.findRecentEvents(anyInt())).thenReturn(List.of(event));

        mockMvc.perform(get("/api/observer/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("evt-1"))
                .andExpect(jsonPath("$[0].mode").value("wildlife"));
    }

    @Test
    void getRecentEventsRejectsInvalidLimit() throws Exception {
        mockMvc.perform(get("/api/observer/events").param("limit", "0"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/observer/events").param("limit", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getSpeciesRankingReturnsOk() throws Exception {
        SpeciesRecordEntity species = new SpeciesRecordEntity("Kohlmeise", "bird");
        when(observerService.getSpeciesRanking()).thenReturn(List.of(species));

        mockMvc.perform(get("/api/observer/species"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].normalizedNameDe").value("Kohlmeise"));
    }

    @Test
    void getStatsReturnsOk() throws Exception {
        when(observerService.getTotalEventCount()).thenReturn(42L);
        when(observerService.getTotalSpeciesCount()).thenReturn(0L);

        mockMvc.perform(get("/api/observer/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEvents").value(42))
                .andExpect(jsonPath("$.totalSpecies").value(0));
    }

    @Test
    void getEventByIdReturnsOk() throws Exception {
        ObservationEventEntity event = new ObservationEventEntity("evt-42");
        event.setTimestamp(Instant.now());
        event.setState(EventState.COMPLETED);
        event.setMode("wildlife");

        when(observerService.findEventById(eq("evt-42"))).thenReturn(Optional.of(event));

        mockMvc.perform(get("/api/observer/events/evt-42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("evt-42"));
    }

    @Test
    void getEventByIdReturns404WhenNotFound() throws Exception {
        when(observerService.findEventById(eq("nonexistent"))).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/observer/events/nonexistent"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getStatusReturnsOk() throws Exception {
        when(observerProperties.isEnabled()).thenReturn(true);
        when(observerProperties.getDefaultProfile()).thenReturn("birdhouse");
        when(observerProperties.getPreferredAiProvider()).thenReturn("mock");
        ObserverProperties.Folder folder = new ObserverProperties.Folder();
        when(observerProperties.getFolder()).thenReturn(folder);

        mockMvc.perform(get("/api/observer/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.defaultProfile").value("birdhouse"));
    }

    @Test
    void getProfilesReturnsOk() throws Exception {
        mockMvc.perform(get("/api/observer/profiles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].name").value("birdhouse"));
    }

    // ---- New endpoint tests ----

    @Test
    void getProfileByNameReturnsOk() throws Exception {
        mockMvc.perform(get("/api/observer/profiles/birdhouse"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("birdhouse"))
                .andExpect(jsonPath("$.mode").value("wildlife"))
                .andExpect(jsonPath("$.focusMode").value("auto"));
    }

    @Test
    void getProfileByNameReturns404ForUnknown() throws Exception {
        mockMvc.perform(get("/api/observer/profiles/nonexistent"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteEventReturns204WhenFound() throws Exception {
        when(observerService.deleteEvent(eq("evt-del"))).thenReturn(true);

        mockMvc.perform(delete("/api/observer/events/evt-del").with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteEventReturns404WhenNotFound() throws Exception {
        when(observerService.deleteEvent(eq("missing"))).thenReturn(false);

        mockMvc.perform(delete("/api/observer/events/missing").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void getDailyStatsReturnsOk() throws Exception {
        when(observerService.getDailyStats(eq(7))).thenReturn(List.of(
                Map.of("date", "2026-04-12", "count", 5L)
        ));

        mockMvc.perform(get("/api/observer/stats/daily"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].date").value("2026-04-12"))
                .andExpect(jsonPath("$[0].count").value(5));
    }

    @Test
    void getDailyStatsRejectsBadRange() throws Exception {
        mockMvc.perform(get("/api/observer/stats/daily").param("days", "0"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/observer/stats/daily").param("days", "91"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getSpeciesDetailReturnsOk() throws Exception {
        SpeciesRecordEntity s = new SpeciesRecordEntity("Kohlmeise", "bird");
        when(observerService.findSpeciesByName(eq("Kohlmeise"))).thenReturn(Optional.of(s));

        mockMvc.perform(get("/api/observer/stats/species/Kohlmeise"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.normalizedNameDe").value("Kohlmeise"))
                .andExpect(jsonPath("$.category").value("bird"))
                .andExpect(jsonPath("$.totalSightings").value(1));
    }

    @Test
    void getSpeciesDetailReturns404WhenNotFound() throws Exception {
        when(observerService.findSpeciesByName(eq("Unknown"))).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/observer/stats/species/Unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateConfigReturnsOk() throws Exception {
        when(observerProperties.isEnabled()).thenReturn(true);
        when(observerProperties.getDefaultProfile()).thenReturn("birdhouse");
        when(observerProperties.getPreferredAiProvider()).thenReturn("mock");

        mockMvc.perform(put("/api/observer/config").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"defaultProfile\":\"birdhouse\",\"preferredAiProvider\":\"mock\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultProfile").value("birdhouse"));
    }

    @Test
    void updateConfigRejectsBadProfile() throws Exception {
        mockMvc.perform(put("/api/observer/config").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"defaultProfile\":\"nonexistent\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void getAiProvidersReturnsOk() throws Exception {
        ObserverProperties.Gemini gemini = new ObserverProperties.Gemini();
        when(observerProperties.getGemini()).thenReturn(gemini);

        mockMvc.perform(get("/api/observer/ai/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].name").value("gemini"))
                .andExpect(jsonPath("$[1].name").value("mock"))
                .andExpect(jsonPath("$[1].configured").value(true));
    }
}
