package com.photographer.focusstack.astro;

import com.photographer.focusstack.common.model.StarPoint;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StarDetectionServiceTest {

    private final StarDetectionService service = new StarDetectionService();

    @Test
    void detectsStarsInSyntheticImage() {
        BufferedImage img = SyntheticStarImageGenerator.generate(200, 200, 15, 42L);
        List<StarPoint> stars = service.detectStars(img);

        assertFalse(stars.isEmpty(), "Should detect at least some stars");
        assertTrue(stars.size() >= 5, "Should detect at least 5 of 15 stars, got " + stars.size());

        for (StarPoint star : stars) {
            assertTrue(star.x() >= 0 && star.x() < 200, "Star x should be in bounds");
            assertTrue(star.y() >= 0 && star.y() < 200, "Star y should be in bounds");
            assertTrue(star.brightness() > 0, "Star brightness should be positive");
        }
    }

    @Test
    void detectsNoStarsInBlankImage() {
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        // All black
        List<StarPoint> stars = service.detectStars(img);
        assertTrue(stars.isEmpty(), "Should detect no stars in blank image");
    }

    @Test
    void focusScoreHigherForSharpImage() {
        BufferedImage sharp = SyntheticStarImageGenerator.generate(200, 200, 20, 42L);
        BufferedImage blurry = SyntheticStarImageGenerator.generateBlurry(200, 200, 20, 42L, 3.0);

        List<StarPoint> sharpStars = service.detectStars(sharp);
        List<StarPoint> blurryStars = service.detectStars(blurry);

        double sharpScore = service.computeFocusScore(sharpStars);
        double blurryScore = service.computeFocusScore(blurryStars);

        assertTrue(sharpScore > blurryScore,
                "Sharp image score (" + sharpScore + ") should be higher than blurry (" + blurryScore + ")");
    }

    @Test
    void focusScoreZeroForNoStars() {
        assertEquals(0.0, service.computeFocusScore(List.of()));
    }
}
