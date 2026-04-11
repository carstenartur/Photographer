package com.photographer.focusstack.face.analysis;

import com.photographer.focusstack.common.model.QualityCheck;

import java.util.List;

/**
 * Interface for face detection, landmark analysis, and quality assessment.
 * Implementations may use face-mesh, landmark detection, or other approaches.
 * Concrete library dependencies are encapsulated in adapter classes.
 */
public interface FaceAnalysisEngine {

    /**
     * Analyzes a face image for quality (sharpness, exposure, face detection).
     *
     * @param imageData the image bytes to analyze
     * @param frameId   the frame identifier for result tracking
     * @return quality check result
     */
    QualityCheck analyzeQuality(byte[] imageData, String frameId);

    /**
     * Detects face landmarks in an image.
     *
     * @param imageData the image bytes
     * @return list of landmark coordinates as [x, y] pairs
     */
    List<double[]> detectLandmarks(byte[] imageData);

    /**
     * Estimates the face angle (yaw) from detected landmarks.
     *
     * @param imageData the image bytes
     * @return estimated yaw angle in degrees (-90 to +90)
     */
    double estimateFaceAngle(byte[] imageData);

    /**
     * Returns the name of this engine implementation.
     */
    String name();
}
