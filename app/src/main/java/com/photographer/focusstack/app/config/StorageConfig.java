package com.photographer.focusstack.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * Type-safe configuration for on-disk storage locations.
 *
 * <p>Bound to the {@code focusstack.storage} prefix in {@code application.yml}.
 * Override via environment variables, e.g.:
 * <ul>
 *   <li>{@code FOCUSSTACK_STORAGE_IMAGES_DIR=/data/images}</li>
 *   <li>{@code FOCUSSTACK_STORAGE_SEARCH_INDEX_DIR=/data/search-index}</li>
 *   <li>{@code FOCUSSTACK_STORAGE_MAX_DISK_USAGE_BYTES=10737418240}</li>
 * </ul>
 *
 * @param imagesDir          directory where captured frame images are stored
 * @param searchIndexDir     directory for the Lucene / Hibernate Search index
 * @param maxDiskUsageBytes  maximum disk space allowed for stored images (bytes)
 */
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
