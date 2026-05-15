package com.photographer.focusstack.common.media;

import java.util.List;
import java.util.Optional;

/**
 * Application-side store of {@link MediaItem}s the system has seen so far.
 *
 * <p>Implementations may persist to a database, JSON file or in-memory map.
 * The interface is deliberately tiny: ingest code adds items as it
 * discovers / downloads them, and queries by camera id.
 */
public interface MediaRepository {

    Optional<MediaItem> findById(String id);

    List<MediaItem> findByCamera(String cameraId);

    MediaItem save(MediaItem item);

    void delete(String id);
}
