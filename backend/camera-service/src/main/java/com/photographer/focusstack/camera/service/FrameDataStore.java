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

    @SuppressWarnings("serial")
    private final Map<String, byte[]> store = Collections.synchronizedMap(
            new LinkedHashMap<>(MAX_ENTRIES, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });

    /** Stores the raw bytes for a frame. */
    public void put(String imageId, byte[] data) {
        if (imageId != null && data != null) {
            store.put(imageId, data);
        }
    }

    /**
     * Retrieves the raw bytes for a frame, or {@code null} if not present.
     */
    public byte[] get(String imageId) {
        return store.get(imageId);
    }

    /** Returns {@code true} if the given frame is available. */
    public boolean contains(String imageId) {
        return store.containsKey(imageId);
    }
}
