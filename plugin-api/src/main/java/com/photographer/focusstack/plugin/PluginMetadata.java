package com.photographer.focusstack.plugin;

/**
 * Metadata describing a Focus Stack plugin.
 *
 * <p>Every plugin extension point should expose metadata so the plugin manager
 * can display information in the UI and check version compatibility.
 *
 * @param id          unique plugin identifier (e.g. {@code "nikon-ptp"})
 * @param name        human-readable display name (e.g. {@code "Nikon PTP Camera"})
 * @param version     semantic version string (e.g. {@code "1.2.0"})
 * @param author      plugin author or organisation
 * @param description short description of what the plugin provides
 * @param license     SPDX license identifier (e.g. {@code "Apache-2.0"})
 */
public record PluginMetadata(
        String id,
        String name,
        String version,
        String author,
        String description,
        String license) {

    /** Convenience factory for minimal metadata. */
    public static PluginMetadata of(String id, String name, String version) {
        return new PluginMetadata(id, name, version, "", "", "");
    }
}
