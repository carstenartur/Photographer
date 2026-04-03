package com.canon.focusstack.analysis.service;

import com.canon.focusstack.common.analysis.AnalysisResult;
import com.canon.focusstack.common.model.StackingParameters;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class StackingAdvisor {

    private final SharpnessAnalyzer sharpnessAnalyzer;
    private final MotionDetector motionDetector;
    private final InformationGainEstimator informationGainEstimator;

    public StackingAdvisor(SharpnessAnalyzer sharpnessAnalyzer,
                           MotionDetector motionDetector,
                           InformationGainEstimator informationGainEstimator) {
        this.sharpnessAnalyzer = sharpnessAnalyzer;
        this.motionDetector = motionDetector;
        this.informationGainEstimator = informationGainEstimator;
    }

    /**
     * Analyzes a frame and makes a continue/stop recommendation.
     */
    public AnalysisResult analyzeFrame(byte[] imageData,
                                       byte[] previousFrame,
                                       List<AnalysisResult> history,
                                       StackingParameters params) {
        double sharpnessScore = sharpnessAnalyzer.calculateSharpness(imageData);
        double sharpRegionPercentage = estimateSharpRegion(sharpnessScore);

        MotionDetector.MotionResult motionResult;
        if (params.motionDetectionEnabled() && previousFrame != null) {
            motionResult = motionDetector.detectMotion(imageData, previousFrame);
        } else {
            motionResult = new MotionDetector.MotionResult(0.0, false, 0.15);
        }

        // Build a preliminary result for gain estimation
        AnalysisResult preliminary = new AnalysisResult(
                UUID.randomUUID().toString(),
                sharpnessScore,
                sharpRegionPercentage,
                motionResult.motionDetected(),
                motionResult.motionScore(),
                0.0,
                true
        );

        double infoGain = informationGainEstimator.estimateGain(history, preliminary);

        boolean recommendContinue = shouldContinue(
                infoGain, motionResult, history, params);

        return new AnalysisResult(
                preliminary.imageId(),
                sharpnessScore,
                sharpRegionPercentage,
                motionResult.motionDetected(),
                motionResult.motionScore(),
                infoGain,
                recommendContinue
        );
    }

    private boolean shouldContinue(double infoGain,
                                   MotionDetector.MotionResult motionResult,
                                   List<AnalysisResult> history,
                                   StackingParameters params) {
        // Abort if max frames reached
        if (history != null && history.size() >= params.maxFrames()) {
            return false;
        }
        // Abort if motion detected
        if (motionResult.motionDetected()) {
            return false;
        }
        // Abort if information gain below threshold
        if (infoGain < params.abortThreshold()) {
            return false;
        }
        return true;
    }

    private double estimateSharpRegion(double sharpnessScore) {
        return Math.min(1.0, sharpnessScore * 1.2);
    }
}
