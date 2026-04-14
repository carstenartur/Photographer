package com.photographer.focusstack.astro;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

class NoOpStarTrailDeblurServiceTest {

    @Test
    void returnsSameImage() {
        NoOpStarTrailDeblurService service = new NoOpStarTrailDeblurService();
        BufferedImage img = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        assertSame(img, service.deblur(img));
    }

    @Test
    void isNotEnabled() {
        assertFalse(new NoOpStarTrailDeblurService().isEnabled());
    }
}
