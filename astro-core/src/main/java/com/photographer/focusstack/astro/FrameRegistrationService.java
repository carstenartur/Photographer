package com.photographer.focusstack.astro;

import com.photographer.focusstack.common.model.FrameTransform;
import com.photographer.focusstack.common.model.StarPoint;

import java.util.Comparator;
import java.util.List;

/**
 * Estimates frame-to-frame transforms by matching star positions between frames.
 * Initially supports translation-only estimation; architecture supports future rotation.
 */
public class FrameRegistrationService {

    private final double matchRadius;
    private final double maxRegistrationError;

    public FrameRegistrationService() {
        this(10.0, 20.0);
    }

    public FrameRegistrationService(double matchRadius, double maxRegistrationError) {
        this.matchRadius = matchRadius;
        this.maxRegistrationError = maxRegistrationError;
    }

    /** Returns the configured maximum registration error threshold. */
    public double getMaxRegistrationError() {
        return maxRegistrationError;
    }

    /**
     * Result of registering one frame to the reference.
     */
    public record RegistrationResult(FrameTransform transform, double error, int matchedStars, boolean accepted) {}

    /**
     * Estimates the transform from frame stars to reference stars.
     * Uses brightest star matching with translation estimation.
     */
    public RegistrationResult register(List<StarPoint> referenceStars, List<StarPoint> frameStars) {
        if (referenceStars.isEmpty() || frameStars.isEmpty()) {
            return new RegistrationResult(FrameTransform.identity(), Double.MAX_VALUE, 0, false);
        }

        // Sort by brightness descending, take top N
        int maxStars = Math.min(20, Math.min(referenceStars.size(), frameStars.size()));
        List<StarPoint> refTop = referenceStars.stream()
                .sorted(Comparator.comparingDouble(StarPoint::brightness).reversed())
                .limit(maxStars)
                .toList();
        List<StarPoint> frameTop = frameStars.stream()
                .sorted(Comparator.comparingDouble(StarPoint::brightness).reversed())
                .limit(maxStars)
                .toList();

        // Try to match stars and estimate translation
        double bestDx = 0, bestDy = 0;
        int bestMatches = 0;
        double bestError = Double.MAX_VALUE;

        for (StarPoint refStar : refTop) {
            for (StarPoint frameStar : frameTop) {
                double dx = refStar.x() - frameStar.x();
                double dy = refStar.y() - frameStar.y();

                // Count how many other stars match with this translation
                int matches = 0;
                double totalError = 0;
                for (StarPoint rs : refTop) {
                    for (StarPoint fs : frameTop) {
                        double predX = fs.x() + dx;
                        double predY = fs.y() + dy;
                        double dist = Math.sqrt(Math.pow(predX - rs.x(), 2) + Math.pow(predY - rs.y(), 2));
                        if (dist < matchRadius) {
                            matches++;
                            totalError += dist;
                            break;
                        }
                    }
                }

                if (matches > bestMatches || (matches == bestMatches && totalError < bestError)) {
                    bestMatches = matches;
                    bestDx = dx;
                    bestDy = dy;
                    bestError = matches > 0 ? totalError / matches : Double.MAX_VALUE;
                }
            }
        }

        boolean accepted = bestMatches >= 3 && bestError < maxRegistrationError;
        FrameTransform transform = new FrameTransform(0.0, bestDx, bestDy, null);
        return new RegistrationResult(transform, bestError, bestMatches, accepted);
    }
}
