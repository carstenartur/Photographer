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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

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

    /** Disk-backed original and its camera filename. */
    public record OriginalFrame(String fileName, Path path) {}

    private record FramePaths(Path image, Path metadata) {}

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
        this.storageDir = Path.of(imagesDir).toAbsolutePath().normalize();
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
        FramePaths paths = framePaths(imageId);
        if (paths == null || data == null) {
            return;
        }
        String safeName = safeFileName(imageId, fileName);
        cache.put(imageId, new FrameEntry(
                safeName, cloneOnPut ? data.clone() : data));

        try {
            Files.write(paths.image(), data, WRITE, CREATE, TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
            writeMetadata(paths.metadata(), safeName);
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
        FramePaths paths = framePaths(imageId);
        if (paths == null || sourceFile == null) {
            throw new IllegalArgumentException("A valid imageId and sourceFile are required");
        }
        String safeName = safeFileName(imageId, fileName);
        Path target = paths.image();
        Path metadata = paths.metadata();
        boolean targetCommitted = false;
        try {
            if (!Files.isRegularFile(sourceFile, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("streamed frame source is not a regular file");
            }
            moveOrCopy(sourceFile, target);
            targetCommitted = true;
            writeMetadata(metadata, safeName);
            cache.invalidate(imageId);
        } catch (IOException exception) {
            if (targetCommitted) {
                try {
                    Files.deleteIfExists(target);
                    Files.deleteIfExists(metadata);
                } catch (IOException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
            }
            try {
                Files.deleteIfExists(sourceFile);
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw new UncheckedIOException("Failed to commit streamed frame", exception);
        }
    }

    private static void writeMetadata(Path path, String fileName) throws IOException {
        Files.writeString(path, fileName, WRITE, CREATE, TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
    }

    private static void moveOrCopy(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException moveFailure) {
            try {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                Files.delete(source);
            } catch (IOException copyFailure) {
                copyFailure.addSuppressed(moveFailure);
                throw copyFailure;
            }
        }
    }

    /** Returns the original filename without reading the frame payload. */
    public String getFileName(String imageId) {
        FramePaths paths = framePaths(imageId);
        return paths == null ? null : getFileName(imageId, paths.metadata());
    }

    private String getFileName(String imageId, Path metaFile) {
        FrameEntry cached = cache.getIfPresent(imageId);
        if (cached != null) {
            return cached.fileName();
        }
        if (!Files.isRegularFile(metaFile, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try (var input = Files.newInputStream(metaFile, LinkOption.NOFOLLOW_LINKS)) {
            String value = StandardCharsets.UTF_8.newDecoder()
                    .decode(ByteBuffer.wrap(input.readAllBytes())).toString().trim();
            return value.isEmpty() ? null : value;
        } catch (IOException exception) {
            log.warn("Failed to read frame metadata {}: {}",
                    imageId, exception.getMessage());
            return null;
        }
    }

    /** Retrieves the frame entry, checking cache first and then disk. */
    public FrameEntry getEntry(String imageId) {
        FramePaths paths = framePaths(imageId);
        if (paths == null) {
            return null;
        }
        FrameEntry cached = cache.getIfPresent(imageId);
        if (cached != null) {
            return cached;
        }
        return loadFromDisk(imageId, paths);
    }

    /** Retrieves just the raw bytes for a frame, or {@code null} if absent. */
    public byte[] get(String imageId) {
        FrameEntry entry = getEntry(imageId);
        return entry != null ? entry.data() : null;
    }

    /** Locates a stored original without loading it into the frame cache. */
    public OriginalFrame getOriginalFrame(String imageId) {
        FramePaths paths = framePaths(imageId);
        if (paths == null || !Files.isRegularFile(paths.image(), LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        return new OriginalFrame(safeFileName(imageId, getFileName(imageId, paths.metadata())), paths.image());
    }

    /** Returns whether the frame exists in cache or disk storage. */
    public boolean contains(String imageId) {
        FramePaths paths = framePaths(imageId);
        return paths != null && (cache.asMap().containsKey(imageId)
                || Files.isRegularFile(paths.image(), LinkOption.NOFOLLOW_LINKS));
    }

    /** Removes a frame from cache and disk storage. */
    public void remove(String imageId) {
        FramePaths paths = framePaths(imageId);
        if (paths == null) {
            return;
        }
        cache.invalidate(imageId);
        try {
            Files.deleteIfExists(paths.image());
            Files.deleteIfExists(paths.metadata());
        } catch (IOException exception) {
            log.warn("Failed to remove frame {} from disk: {}",
                    imageId, exception.getMessage());
        }
    }

    private FrameEntry loadFromDisk(String imageId, FramePaths paths) {
        Path imageFile = paths.image();
        if (!Files.isRegularFile(imageFile, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try (var input = Files.newInputStream(imageFile, LinkOption.NOFOLLOW_LINKS)) {
            byte[] data = input.readAllBytes();
            FrameEntry entry = new FrameEntry(safeFileName(imageId, getFileName(imageId, paths.metadata())), data);
            cache.put(imageId, entry);
            return entry;
        } catch (IOException exception) {
            log.warn("Failed to load frame {} from disk: {}",
                    imageId, exception.getMessage());
            return null;
        }
    }

    private FramePaths framePaths(String imageId) {
        // Backend IDs may predate UUIDs. Accept a single filename component on any platform.
        if (imageId == null || imageId.isBlank() || imageId.equals(".") || imageId.equals("..")
                || imageId.indexOf('/') >= 0 || imageId.indexOf('\\') >= 0 || imageId.indexOf('\0') >= 0) {
            return null;
        }
        try {
            Path fileName = Path.of(imageId);
            if (fileName.getRoot() != null) {
                return null;
            }
            Path image = storageDir.resolve(fileName).normalize();
            Path metadata = storageDir.resolve(imageId + ".meta").normalize();
            if (!image.startsWith(storageDir) || !metadata.startsWith(storageDir)
                    || !storageDir.equals(image.getParent()) || !storageDir.equals(metadata.getParent())) {
                return null;
            }
            return new FramePaths(image, metadata);
        } catch (InvalidPathException exception) {
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
