package com.photographer.focusstack.camera.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void streamedFileCommitConsumesSourceAndRemainsDiskOnlyUntilRead() throws Exception {
        FrameDataStore store = new FrameDataStore(tempDir.toString(), 1_024);
        Path source = Files.createTempFile(tempDir, "native-", ".part");
        Files.write(source, new byte[]{1, 2, 3, 4, 5});

        store.putFile("native-frame", "IMG_0001.CR3", source);

        assertFalse(Files.exists(source));
        assertTrue(store.contains("native-frame"));
        assertFalse(store.isCached("native-frame"));
        assertEquals("IMG_0001.CR3", store.getFileName("native-frame"));
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, store.get("native-frame"));
        assertTrue(store.isCached("native-frame"));
    }
}
