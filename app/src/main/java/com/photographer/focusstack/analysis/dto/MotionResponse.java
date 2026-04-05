package com.photographer.focusstack.analysis.dto;

public record MotionResponse(double motionScore, boolean motionDetected, double threshold) {}
