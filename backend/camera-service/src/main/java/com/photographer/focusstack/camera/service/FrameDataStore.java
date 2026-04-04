package com.photographer.focusstack.camera.service;

import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory store for frame image data produced during a stacking job.
 *
 * <p>Frames are keyed by their {@code imageId}.  The store is bounded to
 * {@value MAX_ENTRIES} entries (LRU-eviction) to prevent unbounded heap growth.
 *
 * <p>This store is populated by {@link StackingJobService} as each frame is
 * captured and is read by the export endpoint in {@link JobController}.
 */
@Service
public class FrameDataStore {

    private static final int MAX_ENTRIES = 500;

    /**
     * Holds both the raw bytes and the original filename for a captured frame.
     *
     * @param fileName original filename from the camera backend (e.g. {@code IMG_0.jpg})
     * @param data     raw image bytes
     */
    public record FrameEntry(String fileName, byte[] data) {}

    @SuppressWarnings("serial")
    private final Map<String, FrameEntry> store = Collections.synchronizedMap(
            new LinkedHashMap<>(MAX_ENTRIES, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, FrameEntry> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });

    /** Stores the raw bytes and original filename for a frame. */
    public void put(String imageId, String fileName, byte[] data) {
        if (imageId != null && data != null) {
            store.put(imageId, new FrameEntry(fileName != null ? fileName : imageId + ".bin", data));
        }
    }

    /**
     * Retrieves the {@link FrameEntry} for a frame, or {@code null} if not present.
     */
    public FrameEntry getEntry(String imageId) {
        return store.get(imageId);
    }

    /**
     * Retrieves just the raw bytes for a frame, or {@code null} if not present.
     */
    public byte[] get(String imageId) {
        FrameEntry entry = store.get(imageId);
        return entry != null ? entry.data() : null;
    }

    /** Returns {@code true} if the given frame is available. */
    public boolean contains(String imageId) {
        return store.containsKey(imageId);
    }
}
