package com.photographer.focusstack.face.profile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Mock face profile engine for testing.
 * Generates a placeholder 3D profile based on input parameters.
 */
public class MockFaceProfileEngine implements FaceProfileEngine {

    private static final Logger log = LoggerFactory.getLogger(MockFaceProfileEngine.class);

    @Override
    public FaceProfileResult generate(List<byte[]> imageDataList,
                                       List<Double> angles,
                                       List<List<double[]>> landmarksList) {
        if (imageDataList == null || imageDataList.isEmpty()) {
            return new FaceProfileResult(new byte[0], 0, 0.0, "json");
        }
        log.debug("MockFaceProfileEngine: generating profile from {} views", imageDataList.size());

        int vertexCount = imageDataList.size() * 100; // Mock: 100 vertices per view
        double confidence = Math.min(1.0, imageDataList.size() / 18.0); // Full confidence at 18 views
        String mockProfile = String.format(
                "{\"type\":\"face-profile\",\"vertices\":%d,\"views\":%d,\"confidence\":%.2f}",
                vertexCount, imageDataList.size(), confidence);

        return new FaceProfileResult(
                mockProfile.getBytes(StandardCharsets.UTF_8),
                vertexCount,
                confidence,
                "json"
        );
    }

    @Override
    public String name() {
        return "mock-face-profile";
    }
}
