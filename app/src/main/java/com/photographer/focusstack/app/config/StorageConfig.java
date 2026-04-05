package com.photographer.focusstack.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "focusstack.storage")
public record StorageConfig(
        Path imagesDir,
        Path searchIndexDir,
        long maxDiskUsageBytes
) {
    public StorageConfig {
        if (imagesDir == null) {
            imagesDir = Path.of("./data/images");
        }
        if (searchIndexDir == null) {
            searchIndexDir = Path.of("./data/search-index");
        }
        if (maxDiskUsageBytes <= 0) {
            maxDiskUsageBytes = 10L * 1024 * 1024 * 1024; // 10 GB default
        }
    }
}
