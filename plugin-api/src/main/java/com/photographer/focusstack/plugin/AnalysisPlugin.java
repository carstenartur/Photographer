package com.photographer.focusstack.plugin;

/**
 * SPI for pluggable image-analysis algorithms.
 *
 * <p>Implement this interface and register the implementation as a Spring bean
 * (e.g. with {@code @Component}) for built-in plugins, or list it in
 * {@code META-INF/services/com.photographer.focusstack.plugin.AnalysisPlugin}
 * for external runtime-loaded plugins, to make it available via
 * the {@code AnalysisPluginRegistry} and the REST endpoints at
 * {@code /api/analysis/plugins}.
 */
public interface AnalysisPlugin {

    /**
     * Unique, URL-safe name for this plugin (e.g. {@code "sharpness"}).
     * Used as the path variable in {@code POST /api/analysis/plugins/{name}/analyze}.
     */
    String name();

    /**
     * Analyzes the supplied raw image bytes and returns a result.
     *
     * @param imageData raw bytes of the image (PNG, JPEG, or synthetic byte array)
     * @param context   optional parameters/hints from the caller
     * @return analysis result — never {@code null}
     */
    AnalysisPluginResult analyze(byte[] imageData, AnalysisContext context);
}
