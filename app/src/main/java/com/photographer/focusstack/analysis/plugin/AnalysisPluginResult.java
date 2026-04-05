package com.photographer.focusstack.analysis.plugin;

/**
 * Result produced by an {@link AnalysisPlugin}.
 *
 * @param pluginName   name of the plugin that produced this result
 * @param score        primary numeric result (semantics depend on the plugin)
 * @param label        human-readable description of the score (e.g. "sharpness")
 * @param metadata     optional additional data as a JSON-serialisable string map
 */
public record AnalysisPluginResult(
        String pluginName,
        double score,
        String label,
        java.util.Map<String, String> metadata) {

    /** Convenience factory with no metadata. */
    public static AnalysisPluginResult of(String pluginName, double score, String label) {
        return new AnalysisPluginResult(pluginName, score, label, java.util.Map.of());
    }
}
