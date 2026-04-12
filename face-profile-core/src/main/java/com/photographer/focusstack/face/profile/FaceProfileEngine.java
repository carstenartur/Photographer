package com.photographer.focusstack.face.profile;

import com.photographer.focusstack.common.model.FaceProfileJob;

import java.util.List;

/**
 * Interface for generating an approximated 3D face profile by fusing
 * multiple face views captured from different angles.
 * This is a face-specific approach, not a general photogrammetry system.
 * Concrete library dependencies are encapsulated in adapter classes.
 */
public interface FaceProfileEngine {

    /**
     * Generates a 3D face profile from multiple views.
     *
     * @param imageDataList  ordered list of face images at different angles
     * @param angles         the capture angle for each image (same order)
     * @param landmarksList  detected landmarks per image (from FaceAnalysisEngine)
     * @return the 3D profile result containing vertex data and metadata
     */
    FaceProfileResult generate(List<byte[]> imageDataList,
                               List<Double> angles,
                               List<List<double[]>> landmarksList);

    /**
     * Returns the name of this engine implementation.
     */
    String name();

    /**
     * Result of 3D face profile generation.
     *
     * @param profileData  the 3D profile data (format depends on implementation)
     * @param vertexCount  number of vertices in the generated mesh
     * @param confidence   overall confidence score (0.0 to 1.0)
     * @param format       output format identifier (e.g. "obj", "ply", "json")
     */
    record FaceProfileResult(
            byte[] profileData,
            int vertexCount,
            double confidence,
            String format
    ) {
    }
}
