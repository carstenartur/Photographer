package com.photographer.focusstack.camera.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

/**
 * Disk-backed store for frame image data produced during a stacking job.
 *
 * <p>Each frame is written to disk under the configured
 * {@code focusstack.storage.images-dir} directory so that captured images
 * survive application restarts. An in-memory cache (bounded by total byte size)
 * avoids repeated disk reads for recently accessed frames.</p>
 */
@Service
public class FrameDataStore {

    private static final Logger log = LoggerFactory.getLogger(FrameDataStore.class);

    /** Holds both the raw bytes and the original filename for a captured frame. */
    public record FrameEntry(String fileName, byte[] data) {}

    /** Hot-cache: avoids disk reads for recently accessed frames. */
    private final Cache<String, FrameEntry> cache;

    private final Path storageDir;
    private final boolean cloneOnPut;

    @Autowired
    public FrameDataStore(
            @Value("${focusstack.storage.images-dir:./data/images}") String imagesDir,
            @Value("${focusstack.cache.frame-store.max-bytes:1073741824}") long maxCacheBytes) {
        this(imagesDir, maxCacheBytes, true, true);
    }

    private FrameDataStore(
            String imagesDir,
            long maxCacheBytes,
            boolean logConfiguredLimit,
            boolean cloneOnPut) {
        long effectiveMaxBytes = Math.max(1L, maxCacheBytes);
        this.cloneOnPut = cloneOnPut;
        this.cache = Caffeine.newBuilder()
                .maximumWeight(effectiveMaxBytes)
                .weigher((String key, FrameEntry value) -> value.data().length)
                .expireAfterAccess(30, TimeUnit.MINUTES)
                .build();
        this.storageDir = Path.of(imagesDir);
        try {
            Files.createDirectories(storageDir);
        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "Cannot create images directory: " + storageDir, exception);
        }
        if (logConfiguredLimit) {
            log.info("FrameDataStore initialized — images dir: {}, cache max bytes: {}",
                    storageDir.toAbsolutePath(), effectiveMaxBytes);
        } else {
            log.info("FrameDataStore initialized — images dir: {}",
                    storageDir.toAbsolutePath());
        }
    }

    /** Stores the raw bytes and original filename for a frame (memory + disk). */
    public void put(String imageId, String fileName, byte[] data) {
        if (imageId == null || data == null) {
            return;
        }
        String safeName = safeFileName(imageId, fileName);
        cache.put(imageId, new FrameEntry(
                safeName, cloneOnPut ? data.clone() : data));

        try {
            Files.write(storageDir.resolve(imageId), data);
            Files.writeString(storageDir.resolve(imageId + ".meta"), safeName);
        } catch (IOException exception) {
            log.error("Failed to persist frame {} to disk: {}",
                    imageId, exception.getMessage());
        }
    }

    /**
     * Commits a previously streamed temporary file without loading it into memory.
     *
     * <p>The source file is consumed by this method. Large native RAW frames remain
     * disk-only until a later explicit read; the hot cache is invalidated rather
     * than populated.</p>
     */
    public void putFile(String imageId, String fileName, Path sourceFile) {
        if (imageId == null || sourceFile == null) {
            throw new IllegalArgumentException("imageId and sourceFile are required");
        }
        String safeName = safeFileName(imageId, fileName);
        Path target = storageDir.resolve(imageId);
        try {
            if (!Files.isRegularFile(sourceFile)) {
                throw new IOException("streamed frame source is not a regular file");
            }
            Files.move(sourceFile, target, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(storageDir.resolve(imageId + ".meta"), safeName);
            cache.invalidate(imageId);
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(sourceFile);
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw new UncheckedIOException("Failed to commit streamed frame", exception);
        }
    }

    /** Returns the original filename without reading the frame payload. */
    public String getFileName(String imageId) {
        if (imageId == null) {
            return null;
        }
        FrameEntry cached = cache.getIfPresent(imageId);
        if (cached != null) {
            return cached.fileName();
        }
        Path metaFile = storageDir.resolve(imageId + ".meta");
        if (!Files.exists(metaFile)) {
            return null;
        }
        try {
            String value = Files.readString(metaFile).trim();
            return value.isEmpty() ? null : value;
        } catch (IOException exception) {
            log.warn("Failed to read frame metadata {}: {}",
                    imageId, exception.getMessage());
            return null;
        }
    }

    /** Retrieves the frame entry, checking cache first and then disk. */
    public FrameEntry getEntry(String imageId) {
        if (imageId == null) {
            return null;
        }
        FrameEntry cached = cache.getIfPresent(imageId);
        if (cached != null) {
            return cached;
        }
        return loadFromDisk(imageId);
    }

    /** Retrieves just the raw bytes for a frame, or {@code null} if absent. */
    public byte[] get(String imageId) {
        FrameEntry entry = getEntry(imageId);
        return entry != null ? entry.data() : null;
    }

    /** Returns whether the frame exists in cache or disk storage. */
    public boolean contains(String imageId) {
        if (imageId == null) {
            return false;
        }
        return cache.asMap().containsKey(imageId)
                || Files.exists(storageDir.resolve(imageId));
    }

    /** Removes a frame from cache and disk storage. */
    public void remove(String imageId) {
        if (imageId == null) {
            return;
        }
        cache.invalidate(imageId);
        try {
            Files.deleteIfExists(storageDir.resolve(imageId));
            Files.deleteIfExists(storageDir.resolve(imageId + ".meta"));
        } catch (IOException exception) {
            log.warn("Failed to remove frame {} from disk: {}",
                    imageId, exception.getMessage());
        }
    }

    private FrameEntry loadFromDisk(String imageId) {
        Path imageFile = storageDir.resolve(imageId);
        if (!Files.exists(imageFile)) {
            return null;
        }
        try {
            byte[] data = Files.readAllBytes(imageFile);
            String fileName = getFileName(imageId);
            if (fileName == null) {
                fileName = imageId + ".bin";
            }
            FrameEntry entry = new FrameEntry(fileName, data);
            cache.put(imageId, entry);
            return entry;
        } catch (IOException exception) {
            log.warn("Failed to load frame {} from disk: {}",
                    imageId, exception.getMessage());
            return null;
        }
    }

    private static String safeFileName(String imageId, String fileName) {
        return fileName != null && !fileName.isBlank()
                ? fileName : imageId + ".bin";
    }

    boolean isCached(String imageId) {
        return imageId != null && cache.getIfPresent(imageId) != null;
    }

    void cleanUpCache() {
        cache.cleanUp();
    }

    int cachedEntryCount() {
        return cache.asMap().size();
    }
}
