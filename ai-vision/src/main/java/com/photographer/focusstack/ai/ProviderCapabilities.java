package com.photographer.focusstack.ai;

import java.util.List;

/**
 * Capabilities advertised by a {@link VisionProvider}.
 */
public record ProviderCapabilities(
        boolean classification,
        boolean description,
        boolean objectDetection,
        boolean multiImageRanking,
        int maxImageSizeBytes,
        List<String> supportedFormats
) {}
