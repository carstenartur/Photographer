package com.photographer.focusstack.astro;

import com.photographer.focusstack.common.model.AstroFocusConfig;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AstroFocusOptimizerTest {

    private final StarDetectionService starDetection = new StarDetectionService();
    private final AstroFocusOptimizer optimizer = new AstroFocusOptimizer(starDetection);

    @Test
    void evaluateReturnsScoreAndStarCount() {
        BufferedImage img = SyntheticStarImageGenerator.generate(200, 200, 15, 42L);
        AstroFocusOptimizer.FocusEvaluation eval = optimizer.evaluate(img, 5);

        assertEquals(5, eval.focusPosition());
        assertTrue(eval.score() > 0, "Score should be positive for star image");
        assertTrue(eval.starCount() > 0, "Should detect some stars");
    }

    @Test
    void selectBestPositionChoosesHighestScore() {
        var evals = List.of(
                new AstroFocusOptimizer.FocusEvaluation(0, 1.0, 5),
                new AstroFocusOptimizer.FocusEvaluation(1, 5.0, 10),
                new AstroFocusOptimizer.FocusEvaluation(2, 3.0, 8)
        );
        assertEquals(1, optimizer.selectBestPosition(evals));
    }

    @Test
    void shouldScanReturnsTrueForFocusScan() {
        assertTrue(optimizer.shouldScan(AstroFocusConfig.focusScan(5, 10)));
        assertFalse(optimizer.shouldScan(AstroFocusConfig.manualFixed()));
    }
}
