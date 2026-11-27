package com.photographer.focusstack.camera.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void rejectsIdsThatAreNotChildFileNamesForEveryOperation() throws Exception {
        var store = new FrameDataStore(tempDir.resolve("frames").toString(), 1_024);
        Path outside = Files.write(tempDir.resolve("outside"), new byte[]{42});
        Path metadata = Files.writeString(tempDir.resolve("outside.meta"), "original.jpg");
        Path source = Files.write(tempDir.resolve("transfer.part"), new byte[]{1});

        for (String id : List.of("../outside", outside.toString(), "..\\outside", ".", "..", " ", "name\0")) {
            assertNull(store.getFileName(id));
            assertNull(store.getEntry(id));
            assertNull(store.getOriginalFrame(id));
            assertFalse(store.contains(id));
            store.put(id, "changed.jpg", new byte[]{2});
            assertFalse(store.isCached(id));
            assertThrows(IllegalArgumentException.class, () -> store.putFile(id, "changed.jpg", source));
            store.remove(id);
        }

        assertArrayEquals(new byte[]{42}, Files.readAllBytes(outside));
        assertEquals("original.jpg", Files.readString(metadata));
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(source));
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Creating symbolic links requires privileges on Windows")
    void doesNotFollowPayloadOrMetadataLinks() throws Exception {
        Path directory = tempDir.resolve("frames");
        var store = new FrameDataStore(directory.toString(), 1_024);
        Path outside = Files.write(tempDir.resolve("outside"), new byte[]{42});
        Path metadata = Files.writeString(tempDir.resolve("outside.meta"), "original.jpg");
        Files.createSymbolicLink(directory.resolve("linked"), outside);
        Files.createSymbolicLink(directory.resolve("linked.meta"), metadata);
        Files.createSymbolicLink(directory.resolve("metadata-only.meta"), metadata);
        Files.createSymbolicLink(directory.resolve("streamed.meta"), metadata);

        assertNull(store.getEntry("linked"));
        assertNull(store.getOriginalFrame("linked"));
        assertFalse(store.contains("linked"));
        assertNull(store.getFileName("linked"));
        store.put("linked", "changed.jpg", new byte[]{2});
        store.remove("linked");

        assertNull(store.getFileName("metadata-only"));
        store.put("metadata-only", "changed.jpg", new byte[]{2});

        Path source = Files.write(tempDir.resolve("source.part"), new byte[]{3});
        assertThrows(UncheckedIOException.class, () -> store.putFile("streamed", "changed.jpg", source));
        assertFalse(store.contains("streamed"));

        assertArrayEquals(new byte[]{42}, Files.readAllBytes(outside));
        assertEquals("original.jpg", Files.readString(metadata));
    }

    @Test
    void readsLegacyFlatIdsFromDiskAfterReopeningStore() throws Exception {
        var store = new FrameDataStore(tempDir.toString(), 1_024);
        store.put("frame.version_ä", "original.jpg", new byte[]{42});

        var reopened = new FrameDataStore(tempDir.toString(), 1_024);
        assertEquals("original.jpg", reopened.getFileName("frame.version_ä"));
        assertArrayEquals(new byte[]{42}, reopened.get("frame.version_ä"));
    }
}
