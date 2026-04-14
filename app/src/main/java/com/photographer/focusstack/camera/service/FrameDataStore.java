package com.photographer.focusstack.camera.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Disk-backed store for frame image data produced during a stacking job.
 *
 * <p>Each frame is written to disk under the configured
 * {@code focusstack.storage.images-dir} directory so that captured images
 * survive application restarts. An in-memory LRU cache (bounded to
 * {@value MAX_CACHE_ENTRIES} entries) avoids repeated disk reads for recently
 * accessed frames.
 *
 * <p>This store is populated by {@link StackingJobService} as each frame is
 * captured and is read by the export / image endpoints in the job controller.
 */
@Service
public class FrameDataStore {

    private static final Logger log = LoggerFactory.getLogger(FrameDataStore.class);

    private static final int MAX_CACHE_ENTRIES = 200;

    /**
     * Holds both the raw bytes and the original filename for a captured frame.
     *
     * @param fileName original filename from the camera backend (e.g. {@code IMG_0.jpg})
     * @param data     raw image bytes
     */
    public record FrameEntry(String fileName, byte[] data) {}

    /** Hot-cache: avoids disk reads for recently accessed frames. */
    @SuppressWarnings("serial")
    private final Map<String, FrameEntry> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(MAX_CACHE_ENTRIES, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, FrameEntry> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            });

    private final Path storageDir;

    public FrameDataStore(
            @Value("${focusstack.storage.images-dir:./data/images}") String imagesDir) {
        this.storageDir = Path.of(imagesDir);
        try {
            Files.createDirectories(storageDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create images directory: " + storageDir, e);
        }
        log.info("FrameDataStore initialized — images dir: {}", storageDir.toAbsolutePath());
    }

    /** Stores the raw bytes and original filename for a frame (memory + disk). */
    public void put(String imageId, String fileName, byte[] data) {
        if (imageId == null || data == null) {
            return;
        }
        String safeName = fileName != null ? fileName : imageId + ".bin";
        cache.put(imageId, new FrameEntry(safeName, data));

        // Persist to disk
        try {
            Path imageFile = storageDir.resolve(imageId);
            Files.write(imageFile, data);
            // Store the filename in a companion ".meta" file
            Files.writeString(storageDir.resolve(imageId + ".meta"), safeName);
        } catch (IOException e) {
            log.error("Failed to persist frame {} to disk: {}", imageId, e.getMessage());
        }
    }

    /**
     * Retrieves the {@link FrameEntry} for a frame, or {@code null} if not present.
     * Checks the in-memory cache first, then falls back to disk.
     */
    public FrameEntry getEntry(String imageId) {
        if (imageId == null) {
            return null;
        }
        FrameEntry cached = cache.get(imageId);
        if (cached != null) {
            return cached;
        }
        // Try loading from disk
        return loadFromDisk(imageId);
    }

    /**
     * Retrieves just the raw bytes for a frame, or {@code null} if not present.
     */
    public byte[] get(String imageId) {
        FrameEntry entry = getEntry(imageId);
        return entry != null ? entry.data() : null;
    }

    /** Returns {@code true} if the given frame is available (cache or disk). */
    public boolean contains(String imageId) {
        if (imageId == null) {
            return false;
        }
        if (cache.containsKey(imageId)) {
            return true;
        }
        return Files.exists(storageDir.resolve(imageId));
    }

    /** Removes a frame from both the in-memory cache and disk storage. */
    public void remove(String imageId) {
        if (imageId == null) return;
        cache.remove(imageId);
        try {
            Files.deleteIfExists(storageDir.resolve(imageId));
            Files.deleteIfExists(storageDir.resolve(imageId + ".meta"));
        } catch (IOException e) {
            log.warn("Failed to remove frame {} from disk: {}", imageId, e.getMessage());
        }
    }

    // ── Internal helpers ────────────────────────────────────────────────

    private FrameEntry loadFromDisk(String imageId) {
        Path imageFile = storageDir.resolve(imageId);
        if (!Files.exists(imageFile)) {
            return null;
        }
        try {
            byte[] data = Files.readAllBytes(imageFile);
            String fileName = imageId + ".bin";
            Path metaFile = storageDir.resolve(imageId + ".meta");
            if (Files.exists(metaFile)) {
                fileName = Files.readString(metaFile).trim();
            }
            FrameEntry entry = new FrameEntry(fileName, data);
            cache.put(imageId, entry); // warm the cache
            return entry;
        } catch (IOException e) {
            log.warn("Failed to load frame {} from disk: {}", imageId, e.getMessage());
            return null;
        }
    }
}
