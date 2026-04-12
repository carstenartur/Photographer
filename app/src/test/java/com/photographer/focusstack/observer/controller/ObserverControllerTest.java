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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
}
