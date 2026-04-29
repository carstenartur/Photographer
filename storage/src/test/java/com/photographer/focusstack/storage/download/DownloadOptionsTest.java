package com.photographer.focusstack.storage.download;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DownloadOptionsTest {

    @Test
    void safeDefault_isNonDestructive() {
        DownloadOptions o = DownloadOptions.safeDefault();
        assertFalse(o.deleteAfterDownload());
        assertFalse(o.validateAfterDownload());
        assertEquals(RawJpegDeletionPolicy.DELETE_DOWNLOADED_ONLY, o.deletionPolicy());
    }

    @Test
    void deleteWithValidation_enablesBothFlags() {
        DownloadOptions o = DownloadOptions.deleteWithValidation();
        assertTrue(o.deleteAfterDownload());
        assertTrue(o.validateAfterDownload());
        assertEquals(RawJpegDeletionPolicy.DELETE_DOWNLOADED_ONLY, o.deletionPolicy());
    }

    @Test
    void withers_returnNewInstancesWithoutMutating() {
        DownloadOptions base = DownloadOptions.safeDefault();
        DownloadOptions withDel = base.withDeleteAfterDownload(true);
        DownloadOptions withValid = base.withValidateAfterDownload(true);
        DownloadOptions withPolicy = base.withDeletionPolicy(RawJpegDeletionPolicy.DELETE_BOTH_PAIR);

        assertFalse(base.deleteAfterDownload(), "base must not mutate");
        assertTrue(withDel.deleteAfterDownload());
        assertTrue(withValid.validateAfterDownload());
        assertEquals(RawJpegDeletionPolicy.DELETE_BOTH_PAIR, withPolicy.deletionPolicy());
    }

    @Test
    void nullPolicy_isRejected() {
        assertThrows(NullPointerException.class,
                () -> new DownloadOptions(false, false, null));
    }

    @Test
    void rawJpegDeletionPolicy_safeDefaultIsDownloadedOnly() {
        assertEquals(RawJpegDeletionPolicy.DELETE_DOWNLOADED_ONLY,
                RawJpegDeletionPolicy.safeDefault());
    }
}
