package com.photographer.focusstack.common.ai;

import java.nio.file.Path;

/**
 * Input bundle for a scene-analysis pass (live-view or test image).
 *
 * <p>Either {@link #frameBytes()} or {@link #framePath()} must be non-null.
 * The {@code mimeType} (e.g. {@code image/jpeg}) lets the analyzer pick the
 * right decoder.
 */
public record SceneAnalysisInput(
        byte[] frameBytes,
        Path framePath,
        String mimeType,
        String cameraId,
        long timestampMillis
) {
    public SceneAnalysisInput {
        if (frameBytes == null && framePath == null) {
            throw new IllegalArgumentException("Either frameBytes or framePath must be provided");
        }
    }
}
