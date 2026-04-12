package com.photographer.focusstack.observer.controller;

import com.photographer.focusstack.app.security.UserRepository;
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

import static org.mockito.ArgumentMatchers.anyInt;
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
        when(observerService.getSpeciesRanking()).thenReturn(List.of());

        mockMvc.perform(get("/api/observer/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEvents").value(42))
                .andExpect(jsonPath("$.totalSpecies").value(0));
    }
}
