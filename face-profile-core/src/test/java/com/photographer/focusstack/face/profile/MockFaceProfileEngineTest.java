package com.photographer.focusstack.face.profile;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MockFaceProfileEngineTest {

    private final MockFaceProfileEngine engine = new MockFaceProfileEngine();

    @Test
    void generate_withViews_returnsProfile() {
        List<byte[]> images = List.of(new byte[]{1}, new byte[]{2}, new byte[]{3});
        List<Double> angles = List.of(0.0, 10.0, 20.0);
        List<List<double[]>> landmarks = List.of(List.of(), List.of(), List.of());

        FaceProfileEngine.FaceProfileResult result = engine.generate(images, angles, landmarks);

        assertNotNull(result);
        assertTrue(result.vertexCount() > 0);
        assertTrue(result.confidence() > 0);
        assertEquals("json", result.format());
        assertTrue(result.profileData().length > 0);
    }

    @Test
    void generate_emptyInput_returnsEmptyProfile() {
        FaceProfileEngine.FaceProfileResult result = engine.generate(List.of(), List.of(), List.of());

        assertEquals(0, result.vertexCount());
        assertEquals(0.0, result.confidence());
    }

    @Test
    void generate_nullInput_returnsEmptyProfile() {
        FaceProfileEngine.FaceProfileResult result = engine.generate(null, null, null);

        assertEquals(0, result.vertexCount());
    }

    @Test
    void generate_moreViews_higherConfidence() {
        List<byte[]> fewImages = List.of(new byte[]{1}, new byte[]{2});
        List<byte[]> manyImages = List.of(
                new byte[]{1}, new byte[]{2}, new byte[]{3}, new byte[]{4},
                new byte[]{5}, new byte[]{6}, new byte[]{7}, new byte[]{8},
                new byte[]{9});

        FaceProfileEngine.FaceProfileResult fewResult = engine.generate(fewImages,
                List.of(0.0, 10.0), List.of(List.of(), List.of()));
        FaceProfileEngine.FaceProfileResult manyResult = engine.generate(manyImages,
                List.of(0.0, 10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0),
                List.of(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(), List.of()));

        assertTrue(manyResult.confidence() > fewResult.confidence());
        assertTrue(manyResult.vertexCount() > fewResult.vertexCount());
    }

    @Test
    void name_returnsMockFaceProfile() {
        assertEquals("mock-face-profile", engine.name());
    }
}
