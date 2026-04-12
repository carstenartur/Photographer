package com.photographer.focusstack.storage;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Abstraction for storing session data under data/sessions/.
 */
public interface SessionStorage {

    /**
     * Creates the directory structure for a session.
     *
     * @param sessionId the session identifier
     * @return the session root directory path
     * @throws IOException if directory creation fails
     */
    Path createSessionDirectory(String sessionId) throws IOException;

    /**
     * Stores a file (image, metadata, result) within a session directory.
     *
     * @param sessionId the session identifier
     * @param fileName  the file name
     * @param data      the file content
     * @return the path to the stored file
     * @throws IOException if the write fails
     */
    Path storeFile(String sessionId, String fileName, byte[] data) throws IOException;

    /**
     * Reads a file from a session directory.
     *
     * @param sessionId the session identifier
     * @param fileName  the file name
     * @return the file content
     * @throws IOException if the read fails
     */
    byte[] readFile(String sessionId, String fileName) throws IOException;

    /**
     * Checks if a session directory exists.
     *
     * @param sessionId the session identifier
     * @return true if the session directory exists
     */
    boolean sessionExists(String sessionId);

    /**
     * Returns the root path for a session.
     *
     * @param sessionId the session identifier
     * @return the session root directory path
     */
    Path getSessionPath(String sessionId);
}
