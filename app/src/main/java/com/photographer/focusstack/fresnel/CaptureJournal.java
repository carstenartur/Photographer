package com.photographer.focusstack.fresnel;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One durable, atomically replaced document per bounded experiment. */
final class CaptureJournal implements Closeable {
    private final Path directory;
    private final FileChannel channel;
    private final FileLock lock;
    final String instanceId;

    CaptureJournal(Path directory) throws IOException {
        this.directory = Files.createDirectories(directory);
        channel = FileChannel.open(directory.resolve("sessions.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            lock = channel.tryLock();
            if (lock == null) throw new IOException("The Fresnel capture journal already has an owner");
            Path identity = directory.resolve("instance.id");
            if (!Files.exists(identity)) {
                writeAtomically(directory, "instance.id", UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII));
            }
            instanceId = UUID.fromString(Files.readString(identity)).toString();
        } catch (RuntimeException | IOException ex) {
            channel.close();
            throw ex;
        }
    }

    List<CaptureSession> load() throws IOException {
        var sessions = new ArrayList<CaptureSession>();
        try (var paths = Files.newDirectoryStream(directory, "*.json")) {
            for (Path path : paths) {
                CaptureSession session = FresnelPlan.JSON.readValue(path.toFile(), CaptureSession.class);
                if (!path.getFileName().toString().equals(UUID.fromString(session.id) + ".json"))
                    throw new IOException("Invalid stored Fresnel session identity");
                sessions.add(session);
            }
        }
        return sessions;
    }

    void save(CaptureSession session) throws IOException {
        writeAtomically(directory, UUID.fromString(session.id) + ".json", FresnelPlan.JSON.writeValueAsBytes(session));
    }

    void delete(String sessionId) throws IOException {
        Files.deleteIfExists(directory.resolve(UUID.fromString(sessionId) + ".json"));
        syncDirectory(directory);
    }

    static void writeAtomically(Path directory, String name, byte[] data) throws IOException {
        Path temp = Files.createTempFile(directory, ".write-", ".tmp");
        try {
            try (var out = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(data);
                while (bytes.hasRemaining()) out.write(bytes);
                out.force(true);
            }
            Files.move(temp, directory.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            syncDirectory(directory);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static void syncDirectory(Path directory) throws IOException {
        try (var parent = FileChannel.open(directory, StandardOpenOption.READ)) {
            parent.force(true);
        } catch (java.nio.file.AccessDeniedException windowsDirectoryHandle) {
            // Windows disallows opening directories as FileChannels.
            if (!System.getProperty("os.name").startsWith("Windows")) throw windowsDirectoryHandle;
        }
    }

    @Override public void close() throws IOException {
        try { lock.release(); } finally { channel.close(); }
    }
}
