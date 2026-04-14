package com.photographer.focusstack.astro;

import com.photographer.focusstack.common.model.StarPoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FrameRegistrationServiceTest {

    private final FrameRegistrationService service = new FrameRegistrationService();

    @Test
    void registersIdenticalFrames() {
        List<StarPoint> stars = List.of(
                new StarPoint(50, 50, 0.9, 2.0),
                new StarPoint(100, 30, 0.8, 1.5),
                new StarPoint(150, 120, 0.85, 2.5),
                new StarPoint(80, 170, 0.7, 1.8)
        );

        var result = service.register(stars, stars);
        assertTrue(result.accepted(), "Identical stars should register successfully");
        assertTrue(Math.abs(result.transform().translationX()) < 1.0, "TX should be ~0");
        assertTrue(Math.abs(result.transform().translationY()) < 1.0, "TY should be ~0");
        assertTrue(result.matchedStars() >= 3, "Should match at least 3 stars");
    }

    @Test
    void registersShiftedFrame() {
        double dx = 5.0, dy = 3.0;
        List<StarPoint> refStars = List.of(
                new StarPoint(50, 50, 0.9, 2.0),
                new StarPoint(100, 30, 0.8, 1.5),
                new StarPoint(150, 120, 0.85, 2.5),
                new StarPoint(80, 170, 0.7, 1.8)
        );
        List<StarPoint> shiftedStars = refStars.stream()
                .map(s -> new StarPoint(s.x() - dx, s.y() - dy, s.brightness(), s.radius()))
                .toList();

        var result = service.register(refStars, shiftedStars);
        assertTrue(result.accepted(), "Shifted frame should register");
        assertEquals(dx, result.transform().translationX(), 2.0, "TX should be ~" + dx);
        assertEquals(dy, result.transform().translationY(), 2.0, "TY should be ~" + dy);
    }

    @Test
    void rejectsEmptyStarLists() {
        var result = service.register(List.of(), List.of());
        assertFalse(result.accepted());
    }
}
