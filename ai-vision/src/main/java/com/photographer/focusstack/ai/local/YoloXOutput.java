package com.photographer.focusstack.ai.local;

import com.photographer.focusstack.observer.events.BoundingBox;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Decodes the raw YOLOX grid and applies non-maximum suppression for birds. */
final class YoloXOutput {

    static final int BOX_COUNT = 3549;
    static final int BOX_VALUES = 85;

    private static final int[] STRIDES = {8, 16, 32};
    private static final int OBJECTNESS = 4;
    private static final int BIRD_PROBABILITY = 5 + 14;
    private static final double SCORE_FLOOR = 0.01;
    private static final double NMS_IOU = 0.45;

    private YoloXOutput() {}

    static List<BoundingBox> decode(FloatBuffer output, YoloXInput.ImageGeometry image) {
        if (output == null || output.remaining() != BOX_COUNT * BOX_VALUES) {
            throw new IllegalArgumentException("The local bird model returned an invalid detection tensor length");
        }
        var candidates = new ArrayList<BoundingBox>();
        int offset = output.position();
        for (int stride : STRIDES) {
            int gridSize = YoloXInput.SIZE / stride;
            for (int y = 0; y < gridSize; y++) {
                for (int x = 0; x < gridSize; x++) {
                    validatePrediction(output, offset);
                    double score = (double) output.get(offset + OBJECTNESS) * output.get(offset + BIRD_PROBABILITY);
                    if (score >= SCORE_FLOOR) {
                        BoundingBox box = decodeBox(output, offset, x, y, stride, image, score);
                        if (box != null) {
                            candidates.add(box);
                        }
                    }
                    offset += BOX_VALUES;
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(BoundingBox::confidence).reversed());
        var retained = new ArrayList<BoundingBox>();
        for (BoundingBox candidate : candidates) {
            if (!overlaps(retained, candidate)) {
                retained.add(candidate);
            }
        }
        return retained;
    }

    private static void validatePrediction(FloatBuffer output, int offset) {
        for (int field = 0; field < BOX_VALUES; field++) {
            float value = output.get(offset + field);
            if (!Float.isFinite(value) || (field >= OBJECTNESS && (value < 0 || value > 1))) {
                throw new IllegalArgumentException("The local bird model returned invalid detection values");
            }
        }
    }

    private static BoundingBox decodeBox(FloatBuffer output, int offset, int x, int y, int stride,
            YoloXInput.ImageGeometry image, double score) {
        double centerX = ((double) output.get(offset) + x) * stride;
        double centerY = ((double) output.get(offset + 1) + y) * stride;
        double width = Math.exp(output.get(offset + 2)) * stride;
        double height = Math.exp(output.get(offset + 3)) * stride;
        if (!Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("The local bird model returned invalid box dimensions");
        }
        double scaledWidth = image.width() * image.scale();
        double scaledHeight = image.height() * image.scale();
        double left = normalized(centerX - width / 2, scaledWidth);
        double top = normalized(centerY - height / 2, scaledHeight);
        double right = normalized(centerX + width / 2, scaledWidth);
        double bottom = normalized(centerY + height / 2, scaledHeight);
        if (right <= left || bottom <= top) {
            return null;
        }
        return new BoundingBox(left, top, right - left, bottom - top, "bird", score);
    }

    private static double normalized(double coordinate, double extent) {
        return Math.clamp(coordinate / extent, 0.0, 1.0);
    }

    private static boolean overlaps(List<BoundingBox> retained, BoundingBox candidate) {
        for (BoundingBox box : retained) {
            double width = Math.min(box.x() + box.width(), candidate.x() + candidate.width())
                    - Math.max(box.x(), candidate.x());
            double height = Math.min(box.y() + box.height(), candidate.y() + candidate.height())
                    - Math.max(box.y(), candidate.y());
            if (width > 0 && height > 0) {
                double intersection = width * height;
                double union = box.width() * box.height() + candidate.width() * candidate.height() - intersection;
                if (intersection / union > NMS_IOU) {
                    return true;
                }
            }
        }
        return false;
    }
}
