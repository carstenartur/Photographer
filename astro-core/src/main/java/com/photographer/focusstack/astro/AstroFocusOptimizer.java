package com.photographer.focusstack.astro;

import com.photographer.focusstack.common.model.AstroFocusConfig;
import com.photographer.focusstack.common.model.AstroFocusStrategy;
import com.photographer.focusstack.common.model.StarPoint;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Evaluates focus quality using star detection heuristics and selects the best focus position
 * from a series of test frames captured at different focus positions.
 */
public class AstroFocusOptimizer {

    private final StarDetectionService starDetectionService;

    public AstroFocusOptimizer(StarDetectionService starDetectionService) {
        this.starDetectionService = starDetectionService;
    }

    /**
     * Result of evaluating a single focus position.
     */
    public record FocusEvaluation(int focusPosition, double score, int starCount) {}

    /**
     * Evaluates a test frame captured at the given focus position.
     */
    public FocusEvaluation evaluate(BufferedImage testFrame, int focusPosition) {
        List<StarPoint> stars = starDetectionService.detectStars(testFrame);
        double score = starDetectionService.computeFocusScore(stars);
        return new FocusEvaluation(focusPosition, score, stars.size());
    }

    /**
     * Selects the best focus position from a list of evaluations.
     * Returns the position with the highest score.
     */
    public int selectBestPosition(List<FocusEvaluation> evaluations) {
        return evaluations.stream()
                .max(java.util.Comparator.comparingDouble(FocusEvaluation::score))
                .map(FocusEvaluation::focusPosition)
                .orElse(0);
    }

    /**
     * Returns true if the config strategy is FOCUS_SCAN.
     */
    public boolean shouldScan(AstroFocusConfig config) {
        return config.strategy() == AstroFocusStrategy.FOCUS_SCAN;
    }
}
