package com.photographer.focusstack.ai.local;

import org.junit.jupiter.api.Test;

import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class YoloXOutputTest {

    @Test
    void clipsBoxesToImageSuppressesOverlapAndIgnoresPadding() {
        FloatBuffer predictions = FloatBuffer.allocate(3549 * 85);
        addPrediction(predictions, 3380, 0.5f, 0.5f, 0.9f);
        addPrediction(predictions, 3381, -0.5f, 0.5f, 0.6f);
        addPrediction(predictions, 3388, 0, 4, 0.7f);
        addPrediction(predictions, 3497, 0, 0, 0.99f);

        var birds = YoloXOutput.decode(predictions, new YoloXInput.ImageGeometry(832, 416, 0.5));

        assertEquals(2, birds.size());
        assertEquals(0.0, birds.get(0).x());
        assertEquals(0.0, birds.get(0).y());
        assertEquals(48.0 / 416, birds.get(0).width(), 1e-7);
        assertEquals(32.0 / 208, birds.get(0).height(), 1e-7);
        assertEquals(0.72, birds.get(0).confidence(), 1e-7);
        assertEquals(224.0 / 416, birds.get(1).x(), 1e-7);
        assertEquals(112.0 / 208, birds.get(1).y(), 1e-7);
    }

    @Test
    void rejectsTruncatedAndNonfiniteTensors() {
        var image = new YoloXInput.ImageGeometry(416, 416, 1);
        assertThrows(IllegalArgumentException.class, () -> YoloXOutput.decode(FloatBuffer.allocate(10), image));

        FloatBuffer predictions = FloatBuffer.allocate(3549 * 85);
        predictions.put(100 * 85 + 84, Float.NaN);
        assertThrows(IllegalArgumentException.class, () -> YoloXOutput.decode(predictions, image));
    }

    private static void addPrediction(FloatBuffer tensor, int row, float x, float y, float objectness) {
        int start = row * 85;
        tensor.put(start, x);
        tensor.put(start + 1, y);
        tensor.put(start + 2, (float) Math.log(2.0));
        tensor.put(start + 4, objectness);
        tensor.put(start + 19, 0.8f);
    }
}
