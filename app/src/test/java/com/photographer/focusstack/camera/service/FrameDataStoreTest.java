package com.photographer.focusstack.camera.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameDataStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void evictsByByteSizeInsteadOfEntryCount() {
        FrameDataStore store = new FrameDataStore(tempDir.toString(), 6);

        store.put("a", "a.bin", new byte[]{1, 2, 3, 4});
        store.put("b", "b.bin", new byte[]{7, 8, 9, 10});

        store.cleanUpCache();

        assertTrue(store.cachedEntryCount() <= 1,
                "byte-size limit should evict entries even when entry count is still low");
    }
}
