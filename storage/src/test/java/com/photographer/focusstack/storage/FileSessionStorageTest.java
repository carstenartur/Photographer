package com.photographer.focusstack.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileSessionStorageTest {

    @TempDir
    Path tempDir;

    private FileSessionStorage storage;

    @BeforeEach
    void setUp() {
        storage = new FileSessionStorage(tempDir);
    }

    @Test
    void createSessionDirectory_createsSubdirectories() throws IOException {
        Path sessionDir = storage.createSessionDirectory("test-session");

        assertTrue(Files.isDirectory(sessionDir.resolve("frames")));
        assertTrue(Files.isDirectory(sessionDir.resolve("brackets")));
        assertTrue(Files.isDirectory(sessionDir.resolve("results")));
        assertTrue(Files.isDirectory(sessionDir.resolve("metadata")));
    }

    @Test
    void storeAndReadFile_roundTrips() throws IOException {
        storage.createSessionDirectory("test-session");
        byte[] data = "test content".getBytes();

        Path stored = storage.storeFile("test-session", "frames/test.jpg", data);

        assertTrue(Files.exists(stored));
        assertArrayEquals(data, storage.readFile("test-session", "frames/test.jpg"));
    }

    @Test
    void sessionExists_returnsTrueForExistingSession() throws IOException {
        storage.createSessionDirectory("test-session");

        assertTrue(storage.sessionExists("test-session"));
    }

    @Test
    void sessionExists_returnsFalseForMissingSession() {
        assertFalse(storage.sessionExists("nonexistent"));
    }

    @Test
    void getSessionPath_returnsCorrectPath() {
        Path path = storage.getSessionPath("my-session");

        assertEquals(tempDir.resolve("my-session"), path);
    }

    @Test
    void storeFile_createsSessionDirectoryIfMissing() throws IOException {
        byte[] data = "auto-create".getBytes();

        Path stored = storage.storeFile("new-session", "frames/auto.jpg", data);

        assertTrue(Files.exists(stored));
        assertTrue(storage.sessionExists("new-session"));
    }
}
