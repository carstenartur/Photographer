package com.photographer.focusstack.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * File-system based implementation of SessionStorage.
 * Stores session data under a configurable base directory with the structure:
 * <pre>
 * data/sessions/{sessionId}/
 *   frames/
 *   brackets/
 *   results/
 *   metadata/
 * </pre>
 */
public class FileSessionStorage implements SessionStorage {

    private static final Logger log = LoggerFactory.getLogger(FileSessionStorage.class);

    private final Path baseDirectory;

    public FileSessionStorage(Path baseDirectory) {
        this.baseDirectory = baseDirectory;
    }

    @Override
    public Path createSessionDirectory(String sessionId) throws IOException {
        Path sessionDir = getSessionPath(sessionId);
        Files.createDirectories(sessionDir.resolve("frames"));
        Files.createDirectories(sessionDir.resolve("brackets"));
        Files.createDirectories(sessionDir.resolve("results"));
        Files.createDirectories(sessionDir.resolve("metadata"));
        log.info("Created session directory: {}", sessionDir);
        return sessionDir;
    }

    @Override
    public Path storeFile(String sessionId, String fileName, byte[] data) throws IOException {
        Path sessionDir = getSessionPath(sessionId);
        if (!Files.exists(sessionDir)) {
            createSessionDirectory(sessionId);
        }
        Path filePath = sessionDir.resolve(fileName);
        Files.createDirectories(filePath.getParent());
        Files.write(filePath, data);
        log.debug("Stored file: {}", filePath);
        return filePath;
    }

    @Override
    public byte[] readFile(String sessionId, String fileName) throws IOException {
        Path filePath = getSessionPath(sessionId).resolve(fileName);
        return Files.readAllBytes(filePath);
    }

    @Override
    public boolean sessionExists(String sessionId) {
        return Files.exists(getSessionPath(sessionId));
    }

    @Override
    public Path getSessionPath(String sessionId) {
        return baseDirectory.resolve(sessionId);
    }
}
