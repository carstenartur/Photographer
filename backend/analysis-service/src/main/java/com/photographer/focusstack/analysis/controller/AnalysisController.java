package com.photographer.focusstack.analysis.controller;

import com.photographer.focusstack.analysis.dto.AnalyzeRequest;
import com.photographer.focusstack.analysis.dto.MotionResponse;
import com.photographer.focusstack.analysis.dto.SharpnessResponse;
import com.photographer.focusstack.analysis.service.ImageJProcessor;
import com.photographer.focusstack.analysis.service.MotionDetector;
import com.photographer.focusstack.analysis.service.SharpnessAnalyzer;
import com.photographer.focusstack.analysis.service.StackingAdvisor;
import com.photographer.focusstack.common.analysis.AnalysisResult;
import com.photographer.focusstack.common.analysis.SharpnessMap;
import com.photographer.focusstack.common.model.QualityMode;
import com.photographer.focusstack.common.model.StackingParameters;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    private final SharpnessAnalyzer sharpnessAnalyzer;
    private final MotionDetector motionDetector;
    private final StackingAdvisor stackingAdvisor;
    private final ImageJProcessor imageJProcessor;
    private final ObjectMapper objectMapper;

    public AnalysisController(SharpnessAnalyzer sharpnessAnalyzer,
                              MotionDetector motionDetector,
                              StackingAdvisor stackingAdvisor,
                              ImageJProcessor imageJProcessor,
                              ObjectMapper objectMapper) {
        this.sharpnessAnalyzer = sharpnessAnalyzer;
        this.motionDetector = motionDetector;
        this.stackingAdvisor = stackingAdvisor;
        this.imageJProcessor = imageJProcessor;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AnalysisResult> analyze(
            @RequestPart("image") MultipartFile image,
            @RequestPart(value = "previousFrame", required = false) MultipartFile previousFrame,
            @RequestPart(value = "request", required = false) String requestJson) throws IOException {

        byte[] imageData = image.getBytes();
        byte[] prevData = previousFrame != null ? previousFrame.getBytes() : null;

        AnalyzeRequest request;
        if (requestJson != null) {
            request = objectMapper.readValue(requestJson, AnalyzeRequest.class);
        } else {
            request = new AnalyzeRequest(
                    new StackingParameters(0, 1, 30, 0.05, true, QualityMode.BALANCED),
                    Collections.emptyList());
        }

        AnalysisResult result = stackingAdvisor.analyzeFrame(
                imageData, prevData, request.history(), request.parameters());

        return ResponseEntity.ok(result);
    }

    @PostMapping(value = "/sharpness", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SharpnessResponse> sharpness(
            @RequestPart("image") MultipartFile image) throws IOException {
        byte[] data = image.getBytes();
        double score;
        if (imageJProcessor.isImageFile(data)) {
            score = imageJProcessor.calculateSharpness(data);
        } else {
            score = sharpnessAnalyzer.calculateSharpness(data);
        }
        return ResponseEntity.ok(new SharpnessResponse(score));
    }

    @PostMapping(value = "/motion", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MotionResponse> motion(
            @RequestPart("currentFrame") MultipartFile currentFrame,
            @RequestPart("previousFrame") MultipartFile previousFrame) throws IOException {
        byte[] currentData = currentFrame.getBytes();
        byte[] previousData = previousFrame.getBytes();
        MotionDetector.MotionResult result;
        if (imageJProcessor.isImageFile(currentData) && imageJProcessor.isImageFile(previousData)) {
            result = imageJProcessor.detectMotion(currentData, previousData, 0.15);
        } else {
            result = motionDetector.detectMotion(currentData, previousData);
        }
        return ResponseEntity.ok(new MotionResponse(
                result.motionScore(), result.motionDetected(), result.threshold()));
    }

    @PostMapping(value = "/sharpness-map", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SharpnessMap> sharpnessMap(
            @RequestPart("image") MultipartFile image,
            @RequestParam(value = "blockSize", defaultValue = "64") int blockSize) throws IOException {
        if (blockSize < 16) {
            return ResponseEntity.badRequest().build();
        }
        SharpnessMap map = imageJProcessor.createSharpnessMap(image.getBytes(), blockSize);
        return ResponseEntity.ok(map);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "analysis-service",
                "imageJVersion", imageJProcessor.getVersion(),
                "engine", "ImageJ"));
    }
}
