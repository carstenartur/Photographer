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
        Path sessionDir = validateSessionPath(sessionId);
        Files.createDirectories(sessionDir.resolve("frames"));
        Files.createDirectories(sessionDir.resolve("brackets"));
        Files.createDirectories(sessionDir.resolve("results"));
        Files.createDirectories(sessionDir.resolve("metadata"));
        log.info("Created session directory: {}", sessionDir);
        return sessionDir;
    }

    @Override
    public Path storeFile(String sessionId, String fileName, byte[] data) throws IOException {
        Path sessionDir = validateSessionPath(sessionId);
        if (!Files.exists(sessionDir)) {
            createSessionDirectory(sessionId);
        }
        Path filePath = validateFilePath(sessionDir, fileName);
        Files.createDirectories(filePath.getParent());
        Files.write(filePath, data);
        log.debug("Stored file: {}", filePath);
        return filePath;
    }

    @Override
    public byte[] readFile(String sessionId, String fileName) throws IOException {
        Path sessionDir = validateSessionPath(sessionId);
        Path filePath = validateFilePath(sessionDir, fileName);
        return Files.readAllBytes(filePath);
    }

    @Override
    public boolean sessionExists(String sessionId) {
        try {
            validateSessionPath(sessionId);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return Files.exists(getSessionPath(sessionId));
    }

    @Override
    public Path getSessionPath(String sessionId) {
        return baseDirectory.resolve(sessionId);
    }

    /**
     * Validates that sessionId does not escape baseDirectory via path traversal.
     */
    private Path validateSessionPath(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            throw new IllegalArgumentException("sessionId must not be null or empty");
        }
        Path resolved = baseDirectory.resolve(sessionId).normalize();
        if (!resolved.startsWith(baseDirectory.normalize())) {
            throw new IllegalArgumentException("Invalid sessionId: path traversal detected");
        }
        return resolved;
    }

    /**
     * Validates that fileName does not escape sessionDir via path traversal.
     */
    private Path validateFilePath(Path sessionDir, String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            throw new IllegalArgumentException("fileName must not be null or empty");
        }
        Path normalized = sessionDir.normalize();
        Path resolved = normalized.resolve(fileName).normalize();
        if (!resolved.startsWith(normalized)) {
            throw new IllegalArgumentException("Invalid fileName: path traversal detected");
        }
        return resolved;
    }
}
