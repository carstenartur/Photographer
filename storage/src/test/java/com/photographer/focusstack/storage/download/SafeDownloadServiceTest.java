package com.photographer.focusstack.storage.download;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SafeDownloadServiceTest {

    private final SafeDownloadService service = new SafeDownloadService();
    private static final byte[] PAYLOAD = new byte[]{1, 2, 3, 4};

    // ---- happy paths ----

    @Test
    void downloadOnly_keptOnCamera() {
        AtomicBoolean deleteCalled = new AtomicBoolean(false);
        SafeDownloadResult r = service.download(
                "IMG_0001.JPG",
                DownloadOptions.safeDefault(),
                /* backendSupportsDelete */ true,
                name -> PAYLOAD,
                null,
                (n, p) -> { deleteCalled.set(true); return false; });

        assertEquals(SafeDownloadResult.Status.DOWNLOADED_KEPT, r.status());
        assertArrayEquals(PAYLOAD, r.bytes());
        assertTrue(r.isDownloaded());
        assertFalse(r.isDeleted());
        assertFalse(deleteCalled.get(), "delete must not be invoked when not requested");
    }

    @Test
    void downloadAndDelete_succeeds() {
        AtomicInteger deleteCalls = new AtomicInteger(0);
        SafeDownloadResult r = service.download(
                "IMG_0001.JPG",
                DownloadOptions.deleteWithValidation(),
                true,
                name -> PAYLOAD,
                (n, b) -> b.length > 0,
                (n, p) -> { deleteCalls.incrementAndGet(); return false; });

        assertEquals(SafeDownloadResult.Status.DOWNLOADED_DELETED, r.status());
        assertTrue(r.isDeleted());
        assertEquals(1, deleteCalls.get());
    }

    // ---- backend capability gate ----

    @Test
    void deleteRequested_butBackendUnsupported_keepsFile() {
        AtomicBoolean deleteCalled = new AtomicBoolean(false);
        SafeDownloadResult r = service.download(
                "IMG_0001.JPG",
                DownloadOptions.deleteWithValidation(),
                /* backendSupportsDelete */ false,
                name -> PAYLOAD,
                (n, b) -> true,
                (n, p) -> { deleteCalled.set(true); return false; });

        assertEquals(SafeDownloadResult.Status.DELETE_UNSUPPORTED, r.status());
        assertArrayEquals(PAYLOAD, r.bytes());
        assertFalse(deleteCalled.get(), "delete must not be invoked when backend does not support it");
    }

    @Test
    void deleteRequested_butNoDeleteStep_keepsFile() {
        SafeDownloadResult r = service.download(
                "IMG_0001.JPG",
                DownloadOptions.deleteWithValidation(),
                true,
                name -> PAYLOAD,
                (n, b) -> true,
                /* deleteStep */ null);
        assertEquals(SafeDownloadResult.Status.DELETE_UNSUPPORTED, r.status());
    }

    // ---- failure paths ----

    @Test
    void downloadIoException_returnsDownloadFailed_andDoesNotDelete() {
        AtomicBoolean deleteCalled = new AtomicBoolean(false);
        SafeDownloadResult r = service.download(
                "IMG_0001.JPG",
                DownloadOptions.deleteWithValidation(),
                true,
                name -> { throw new IOException("usb timeout"); },
                (n, b) -> true,
                (n, p) -> { deleteCalled.set(true); return false; });

        assertEquals(SafeDownloadResult.Status.DOWNLOAD_FAILED, r.status());
        assertNull(r.bytes());
        assertFalse(deleteCalled.get(), "delete must never run when download fails");
        assertTrue(r.errorMessage().contains("usb timeout"));
    }

    @Test
    void downloadReturnsNull_treatedAsFailure() {
        SafeDownloadResult r = service.download(
                "IMG_0001.JPG",
                DownloadOptions.safeDefault(),
                true,
                name -> null,
                null, null);
        assertEquals(SafeDownloadResult.Status.DOWNLOAD_FAILED, r.status());
    }

    @Test
    void validationFails_blocksDeletion() {
        AtomicBoolean deleteCalled = new AtomicBoolean(false);
        SafeDownloadResult r = service.download(
                "IMG_0001.JPG",
                DownloadOptions.deleteWithValidation(),
                true,
                name -> PAYLOAD,
                (n, b) -> false,
                (n, p) -> { deleteCalled.set(true); return false; });

        assertEquals(SafeDownloadResult.Status.VALIDATION_FAILED, r.status());
        assertArrayEquals(PAYLOAD, r.bytes(), "bytes preserved for caller inspection");
        assertFalse(deleteCalled.get(), "delete must never run when validation fails");
    }

    @Test
    void validatorThrows_treatedAsValidationFailure() {
        SafeDownloadResult r = service.download(
                "IMG_0001.JPG",
                DownloadOptions.deleteWithValidation(),
                true,
                name -> PAYLOAD,
                (n, b) -> { throw new RuntimeException("checksum mismatch"); },
                (n, p) -> false);
        assertEquals(SafeDownloadResult.Status.VALIDATION_FAILED, r.status());
        assertTrue(r.errorMessage().contains("checksum mismatch"));
    }

    @Test
    void deleteStepFails_returnsDeleteFailed_withBytes() {
        SafeDownloadResult r = service.download(
                "IMG_0001.JPG",
                DownloadOptions.deleteWithValidation(),
                true,
                name -> PAYLOAD,
                (n, b) -> true,
                (n, p) -> { throw new IOException("camera busy"); });

        assertEquals(SafeDownloadResult.Status.DELETE_FAILED, r.status());
        assertArrayEquals(PAYLOAD, r.bytes(), "bytes are already on local disk; must be returned");
        assertTrue(r.isDownloaded(), "isDownloaded() includes DELETE_FAILED");
        assertFalse(r.isDeleted());
    }

    @Test
    void deletionPolicyPropagatedToDeleteStep() {
        AtomicBoolean bothPair = new AtomicBoolean(false);
        DownloadOptions opts = DownloadOptions.deleteWithValidation()
                .withDeletionPolicy(RawJpegDeletionPolicy.DELETE_BOTH_PAIR);
        service.download("IMG_0001.JPG", opts, true,
                name -> PAYLOAD,
                (n, b) -> true,
                (n, p) -> { bothPair.set(p == RawJpegDeletionPolicy.DELETE_BOTH_PAIR); return true; });
        assertTrue(bothPair.get(), "policy must reach the delete step verbatim");
    }

    @Test
    void pairDeletedFlagPropagatedToResult() {
        SafeDownloadResult r = service.download(
                "IMG_0001.JPG",
                DownloadOptions.deleteWithValidation()
                        .withDeletionPolicy(RawJpegDeletionPolicy.DELETE_BOTH_PAIR),
                true,
                name -> PAYLOAD,
                (n, b) -> true,
                (n, p) -> true);
        assertTrue(r.pairDeleted());
    }

    @Test
    void nullArgs_throw() {
        assertThrows(NullPointerException.class, () -> service.download(
                null, DownloadOptions.safeDefault(), true, n -> PAYLOAD, null, null));
        assertThrows(NullPointerException.class, () -> service.download(
                "x", null, true, n -> PAYLOAD, null, null));
        assertThrows(NullPointerException.class, () -> service.download(
                "x", DownloadOptions.safeDefault(), true, null, null, null));
    }
}
