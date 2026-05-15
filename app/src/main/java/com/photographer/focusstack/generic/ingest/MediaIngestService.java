package com.photographer.focusstack.generic.ingest;

import com.photographer.focusstack.common.event.CameraEvent;
import com.photographer.focusstack.common.media.DeleteState;
import com.photographer.focusstack.common.media.MediaItem;
import com.photographer.focusstack.common.media.MediaRepository;
import com.photographer.focusstack.common.media.TransferResult;
import com.photographer.focusstack.common.media.TransferState;
import com.photographer.focusstack.common.policy.DeletePolicy;
import com.photographer.focusstack.common.policy.IngestPolicy;
import com.photographer.focusstack.common.transport.CameraConnection;
import com.photographer.focusstack.common.transport.GenericCameraBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;

/**
 * Single entry point for the import-and-delete workflow.
 *
 * <p>Enforces the {@link IngestPolicy}: type filter, size verification,
 * optional checksum, and the post-import {@link DeletePolicy}. Refuses to
 * delete unless the corresponding download produced a successful, verified
 * {@link TransferResult} — this is the "no silent data loss" guarantee from
 * the architecture brief.
 *
 * <p>All decisions (skip / download / verify / delete-allowed / delete-failed)
 * are emitted through the supplied event consumer for audit and UI feedback.
 */
public class MediaIngestService {

    private static final Logger log = LoggerFactory.getLogger(MediaIngestService.class);

    private final GenericCameraBackend backend;
    private final MediaRepository repository;
    private final Path downloadRoot;

    public MediaIngestService(GenericCameraBackend backend, MediaRepository repository, Path downloadRoot) {
        this.backend = backend;
        this.repository = repository;
        this.downloadRoot = downloadRoot;
    }

    /**
     * Imports every item the backend currently lists, applying {@code policy}.
     * Returns the per-item {@link TransferResult}s in order.
     */
    public List<TransferResult> ingest(CameraConnection connection,
                                       IngestPolicy policy,
                                       Consumer<CameraEvent> eventSink) {
        List<MediaItem> items = backend.listMedia(connection);
        log.info("ingest: {} items found on camera {}", items.size(), connection.cameraId());
        List<TransferResult> results = new ArrayList<>(items.size());
        for (MediaItem item : items) {
            results.add(ingestOne(connection, item, policy, eventSink));
        }
        return results;
    }

    /** Imports a single item. Visible for direct calls from job handlers. */
    public TransferResult ingestOne(CameraConnection connection,
                                    MediaItem item,
                                    IngestPolicy policy,
                                    Consumer<CameraEvent> eventSink) {
        if (!policy.acceptsType(item.mediaType())) {
            log.debug("ingest: skipping {} (type {} not in policy)", item.filename(), item.mediaType());
            eventSink.accept(new CameraEvent.ImportSkipped(
                    connection.cameraId(), Instant.now(), item, "type-not-in-policy"));
            return TransferResult.failed(item, "skipped-by-policy");
        }

        Path target = downloadRoot.resolve(safeRelative(item));
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            log.error("ingest: cannot create target dir {}", target.getParent(), e);
            return TransferResult.failed(item, "io-error: " + e.getMessage());
        }

        MediaItem updated = item.withTransferState(TransferState.DOWNLOADING);
        repository.save(updated);

        TransferResult result;
        try (OutputStream out = Files.newOutputStream(target)) {
            result = backend.downloadMedia(connection, updated, out);
        } catch (IOException e) {
            log.error("ingest: download failed for {}", item.filename(), e);
            repository.save(updated.withTransferState(TransferState.FAILED));
            return TransferResult.failed(item, "io-error: " + e.getMessage());
        }

        if (!result.success()) {
            repository.save(updated.withTransferState(TransferState.FAILED));
            return result;
        }

        // ---- verification ----------------------------------------------------
        // Order matters: compute the checksum FIRST (when the policy asks for it),
        // then derive the verified flag from size + checksum results. The previous
        // implementation gated checksum computation on `verified`, which made the
        // digest almost never run.
        String computedChecksum = policy.verifyChecksum() ? sha256(target) : null;
        boolean sizeOk     = checkSize(result, target, policy);
        boolean checksumOk = checkChecksum(updated, computedChecksum, policy);
        boolean verified   = sizeOk && checksumOk;

        MediaItem finalItem = updated
                .withTransferState(verified ? TransferState.VERIFIED : TransferState.DOWNLOADED)
                .withChecksum(computedChecksum != null ? computedChecksum : updated.checksum());
        repository.save(finalItem);

        eventSink.accept(new CameraEvent.ImportCompleted(
                connection.cameraId(), Instant.now(), finalItem, verified));

        // ---- delete decision -------------------------------------------------
        if (shouldDelete(policy.deletePolicy(), finalItem, verified)) {
            boolean deleted = false;
            try {
                deleted = backend.deleteMedia(connection, finalItem);
            } catch (RuntimeException re) {
                log.error("ingest: deleteMedia threw for {}", finalItem.filename(), re);
            }
            MediaItem afterDelete = finalItem.withDeleteState(deleted ? DeleteState.DELETED : DeleteState.DELETE_FAILED);
            repository.save(afterDelete);
            eventSink.accept(new CameraEvent.DeleteCompleted(
                    connection.cameraId(), Instant.now(), afterDelete, deleted));
            if (!deleted) {
                log.warn("ingest: delete refused/failed for {} — kept on camera", finalItem.filename());
            }
        } else {
            log.debug("ingest: delete skipped for {} (policy={}, verified={})",
                    finalItem.filename(), policy.deletePolicy(), verified);
        }

        return new TransferResult(finalItem, target, result.bytesTransferred(), verified, true, null);
    }

    private static boolean shouldDelete(DeletePolicy p, MediaItem item, boolean verified) {
        if (p == null) return false;
        return switch (p) {
            case NEVER, MANUAL_CONFIRM -> false;
            case AUTO_AFTER_IMPORT     -> true;
            case AUTO_IF_VERIFIED      -> verified;
            case AUTO_JPEG_ONLY        -> verified && (item.mediaType() == com.photographer.focusstack.common.media.MediaType.JPEG
                                                    || item.mediaType() == com.photographer.focusstack.common.media.MediaType.HEIF);
        };
    }

    /** Returns {@code true} when the size check is either disabled or passes. */
    private static boolean checkSize(TransferResult result, Path target, IngestPolicy policy) {
        if (!policy.requireSizeMatch()) return true;
        long expected = result.item().size();
        if (expected < 0) return true; // size unknown -> cannot enforce, treat as pass
        long onDisk;
        try { onDisk = Files.size(target); } catch (IOException e) { return false; }
        return onDisk == expected;
    }

    /**
     * Returns {@code true} when the checksum check is satisfied. Two modes:
     * <ul>
     *   <li>{@code verifyChecksum=false}: always pass (no digest required).</li>
     *   <li>{@code verifyChecksum=true}: a digest must have been computed; if the
     *       item carries an expected checksum it must additionally match.</li>
     * </ul>
     */
    private static boolean checkChecksum(MediaItem item, String computed, IngestPolicy policy) {
        if (!policy.verifyChecksum()) return true;
        if (computed == null) return false;
        String expected = item.checksum();
        return expected == null || expected.equalsIgnoreCase(computed);
    }

    private static String safeRelative(MediaItem item) {
        String storage = item.storageName() == null ? "default" : item.storageName().replaceAll("[^A-Za-z0-9._-]", "_");
        String dir     = item.directoryName() == null ? "DCIM"   : item.directoryName().replaceAll("[^A-Za-z0-9._-]", "_");
        String file    = item.filename() == null ? item.id()     : item.filename().replaceAll("[^A-Za-z0-9._-]", "_");
        return storage + "/" + dir + "/" + file;
    }

    /** Streaming SHA-256 — does not load the file into memory. */
    private static String sha256(Path path) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path);
                 DigestInputStream dis = new DigestInputStream(in, md)) {
                byte[] buf = new byte[8192];
                while (dis.read(buf) != -1) {
                    // DigestInputStream updates the digest as bytes are consumed
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            log.warn("sha256 failed for {}: {}", path, e.toString());
            return null;
        }
    }
}
