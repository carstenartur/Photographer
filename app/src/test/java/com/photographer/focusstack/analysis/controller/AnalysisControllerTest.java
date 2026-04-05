package com.photographer.focusstack.analysis.controller;

import com.photographer.focusstack.analysis.service.ImageJProcessor;
import com.photographer.focusstack.analysis.service.MotionDetector;
import com.photographer.focusstack.analysis.service.SharpnessAnalyzer;
import com.photographer.focusstack.analysis.service.StackingAdvisor;
import com.photographer.focusstack.common.analysis.AnalysisResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = AnalysisController.class,
        excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class})
class AnalysisControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SharpnessAnalyzer sharpnessAnalyzer;

    @MockBean
    private MotionDetector motionDetector;

    @MockBean
    private StackingAdvisor stackingAdvisor;

    @MockBean
    private ImageJProcessor imageJProcessor;

    @Test
    void healthEndpointShouldReturnUp() throws Exception {
        when(imageJProcessor.getVersion()).thenReturn("1.54j");

        mockMvc.perform(get("/api/analysis/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.imageJVersion").value("1.54j"))
                .andExpect(jsonPath("$.engine").value("ImageJ"));
    }

    @Test
    void sharpnessEndpointShouldReturnScore() throws Exception {
        when(sharpnessAnalyzer.calculateSharpness(any(byte[].class))).thenReturn(0.75);
        when(imageJProcessor.isImageFile(any(byte[].class))).thenReturn(false);

        MockMultipartFile image = new MockMultipartFile(
                "image", "test.raw", "application/octet-stream", new byte[100]);

        mockMvc.perform(multipart("/api/analysis/sharpness").file(image))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sharpnessScore").value(0.75));
    }

    @Test
    void sharpnessEndpointShouldUseImageJForRealImages() throws Exception {
        when(imageJProcessor.isImageFile(any(byte[].class))).thenReturn(true);
        when(imageJProcessor.calculateSharpness(any(byte[].class))).thenReturn(0.85);

        MockMultipartFile image = new MockMultipartFile(
                "image", "test.jpg", "image/jpeg", new byte[100]);

        mockMvc.perform(multipart("/api/analysis/sharpness").file(image))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sharpnessScore").value(0.85));
    }

    @Test
    void motionEndpointShouldReturnResult() throws Exception {
        when(motionDetector.detectMotion(any(byte[].class), any(byte[].class)))
                .thenReturn(new MotionDetector.MotionResult(0.3, true, 0.15));
        when(imageJProcessor.isImageFile(any(byte[].class))).thenReturn(false);

        MockMultipartFile current = new MockMultipartFile(
                "currentFrame", "current.raw", "application/octet-stream", new byte[100]);
        MockMultipartFile previous = new MockMultipartFile(
                "previousFrame", "previous.raw", "application/octet-stream", new byte[100]);

        mockMvc.perform(multipart("/api/analysis/motion").file(current).file(previous))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.motionDetected").value(true))
                .andExpect(jsonPath("$.motionScore").value(0.3));
    }

    @Test
    void analyzeEndpointShouldReturnAnalysisResult() throws Exception {
        AnalysisResult mockResult = new AnalysisResult(
                "test-id", 0.8, 0.6, false, 0.05, 0.7, true);
        when(stackingAdvisor.analyzeFrame(any(), any(), any(), any())).thenReturn(mockResult);

        MockMultipartFile image = new MockMultipartFile(
                "image", "test.raw", "application/octet-stream", new byte[100]);

        mockMvc.perform(multipart("/api/analysis/analyze").file(image))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sharpnessScore").value(0.8))
                .andExpect(jsonPath("$.recommendContinue").value(true));
    }
}
