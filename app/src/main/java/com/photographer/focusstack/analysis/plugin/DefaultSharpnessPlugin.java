package com.photographer.focusstack.analysis.plugin;

import com.photographer.focusstack.analysis.service.ImageJProcessor;
import com.photographer.focusstack.analysis.service.SharpnessAnalyzer;
import com.photographer.focusstack.plugin.AnalysisContext;
import com.photographer.focusstack.plugin.AnalysisPlugin;
import com.photographer.focusstack.plugin.AnalysisPluginResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Default sharpness-analysis plugin.
 *
 * <p>Uses {@link ImageJProcessor} for decodable image files (JPEG, PNG, BMP)
 * and falls back to the raw-pixel {@link SharpnessAnalyzer} for synthetic
 * byte arrays or when ImageJ cannot decode the data.
 */
@Component
public class DefaultSharpnessPlugin implements AnalysisPlugin {

    private final ImageJProcessor imageJProcessor;
    private final SharpnessAnalyzer sharpnessAnalyzer;

    public DefaultSharpnessPlugin(ImageJProcessor imageJProcessor,
                                  SharpnessAnalyzer sharpnessAnalyzer) {
        this.imageJProcessor  = imageJProcessor;
        this.sharpnessAnalyzer = sharpnessAnalyzer;
    }

    @Override
    public String name() {
        return "sharpness";
    }

    @Override
    public AnalysisPluginResult analyze(byte[] imageData, AnalysisContext context) {
        double score;
        String engine;
        if (imageJProcessor.isImageFile(imageData)) {
            score  = imageJProcessor.calculateSharpness(imageData);
            engine = "ImageJ/" + imageJProcessor.getVersion();
        } else {
            score  = sharpnessAnalyzer.calculateSharpness(imageData);
            engine = "laplacian-variance";
        }
        return new AnalysisPluginResult(
                name(), score, "sharpness",
                Map.of("engine", engine));
    }
}
