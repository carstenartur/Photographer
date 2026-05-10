package com.photographer.focusstack.generic.ingest;

import com.photographer.focusstack.common.camera.LiveViewFrame;
import com.photographer.focusstack.common.capability.CapabilitySet;
import com.photographer.focusstack.common.device.BatteryInfo;
import com.photographer.focusstack.common.device.DeviceInfo;
import com.photographer.focusstack.common.device.LensInfo;
import com.photographer.focusstack.common.device.StorageInfo;
import com.photographer.focusstack.common.event.CameraEvent;
import com.photographer.focusstack.common.media.DeleteState;
import com.photographer.focusstack.common.media.MediaItem;
import com.photographer.focusstack.common.media.MediaType;
import com.photographer.focusstack.common.media.TransferResult;
import com.photographer.focusstack.common.media.TransferState;
import com.photographer.focusstack.common.policy.DeletePolicy;
import com.photographer.focusstack.common.policy.IngestPolicy;
import com.photographer.focusstack.common.transport.CameraConnection;
import com.photographer.focusstack.common.transport.GenericCameraBackend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class MediaIngestServiceTest {

    @TempDir Path tmp;

    private InMemoryMediaRepository repo;
    private List<CameraEvent> events;

    @BeforeEach void setUp() {
        repo = new InMemoryMediaRepository();
        events = new ArrayList<>();
    }

    @Test
    void neverPolicy_doesNotDelete_evenWhenDownloadVerified() {
        AtomicBoolean deleteCalled = new AtomicBoolean(false);
        MediaItem item = jpegItem("1", "IMG_0001.JPG", 4);
        FakeBackend backend = new FakeBackend(List.of(item),
                /* writeBytes */ 4, deleteCalled, true);

        var svc = new MediaIngestService(backend, repo, tmp);
        var results = svc.ingest(connection(), new IngestPolicy(false, true, Set.of(), DeletePolicy.NEVER), events::add);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).success()).isTrue();
        assertThat(deleteCalled).isFalse();
        assertThat(repo.findById("1")).get().extracting(MediaItem::deleteState).isEqualTo(DeleteState.PRESENT);
    }

    @Test
    void autoIfVerified_deletes_whenSizeMatches() {
        AtomicBoolean deleteCalled = new AtomicBoolean(false);
        MediaItem item = jpegItem("2", "IMG_0002.JPG", 8);
        FakeBackend backend = new FakeBackend(List.of(item),
                /* writeBytes */ 8, deleteCalled, true);

        var svc = new MediaIngestService(backend, repo, tmp);
        svc.ingest(connection(), new IngestPolicy(false, true, Set.of(), DeletePolicy.AUTO_IF_VERIFIED), events::add);

        assertThat(deleteCalled).isTrue();
        assertThat(repo.findById("2")).get().extracting(MediaItem::deleteState).isEqualTo(DeleteState.DELETED);
        assertThat(events).anyMatch(e -> e instanceof CameraEvent.DeleteCompleted dc && dc.success());
    }

    @Test
    void autoIfVerified_doesNotDelete_whenSizeMismatch() {
        AtomicBoolean deleteCalled = new AtomicBoolean(false);
        MediaItem item = jpegItem("3", "IMG_0003.JPG", 16);
        // backend writes only 4 bytes -> size mismatch -> not verified -> no delete
        FakeBackend backend = new FakeBackend(List.of(item), 4, deleteCalled, true);

        var svc = new MediaIngestService(backend, repo, tmp);
        svc.ingest(connection(), new IngestPolicy(false, true, Set.of(), DeletePolicy.AUTO_IF_VERIFIED), events::add);

        assertThat(deleteCalled).isFalse();
        assertThat(repo.findById("3")).get().extracting(MediaItem::transferState).isEqualTo(TransferState.DOWNLOADED);
    }

    @Test
    void autoJpegOnly_keepsRaw_deletesJpeg() {
        AtomicBoolean deleteCalled = new AtomicBoolean(false);
        MediaItem raw = new MediaItem("4", "cam", "primary", "DCIM", "IMG_0004.CR3",
                null, null, MediaType.RAW, null, 4, null,
                TransferState.DISCOVERED, DeleteState.PRESENT, null);
        FakeBackend backend = new FakeBackend(List.of(raw), 4, deleteCalled, true);

        var svc = new MediaIngestService(backend, repo, tmp);
        svc.ingest(connection(), new IngestPolicy(false, true, Set.of(), DeletePolicy.AUTO_JPEG_ONLY), events::add);

        assertThat(deleteCalled).isFalse();
        assertThat(repo.findById("4")).get().extracting(MediaItem::deleteState).isEqualTo(DeleteState.PRESENT);
    }

    @Test
    void typeFilter_skipsItemsOutsidePolicy() {
        MediaItem raw = new MediaItem("5", "cam", "primary", "DCIM", "IMG.CR3",
                null, null, MediaType.RAW, null, 4, null,
                TransferState.DISCOVERED, DeleteState.PRESENT, null);
        AtomicBoolean deleteCalled = new AtomicBoolean(false);
        FakeBackend backend = new FakeBackend(List.of(raw), 4, deleteCalled, true);

        var svc = new MediaIngestService(backend, repo, tmp);
        var results = svc.ingest(connection(), new IngestPolicy(false, false, Set.of(MediaType.JPEG), DeletePolicy.AUTO_AFTER_IMPORT), events::add);

        assertThat(results.get(0).success()).isFalse();
        assertThat(results.get(0).errorMessage()).isEqualTo("skipped-by-policy");
        assertThat(deleteCalled).isFalse();
    }

    @Test
    void deleteFailure_marksDeleteFailed_andEmitsEvent() {
        AtomicBoolean deleteCalled = new AtomicBoolean(false);
        MediaItem item = jpegItem("6", "IMG_0006.JPG", 4);
        // deleteResult = false simulates camera refusing the delete (e.g. file protected)
        FakeBackend backend = new FakeBackend(List.of(item), 4, deleteCalled, /* deleteResult */ false);

        var svc = new MediaIngestService(backend, repo, tmp);
        svc.ingest(connection(), new IngestPolicy(false, true, Set.of(), DeletePolicy.AUTO_AFTER_IMPORT), events::add);

        assertThat(deleteCalled).isTrue();
        assertThat(repo.findById("6")).get().extracting(MediaItem::deleteState).isEqualTo(DeleteState.DELETE_FAILED);
        assertThat(events).anyMatch(e -> e instanceof CameraEvent.DeleteCompleted dc && !dc.success());
    }

    // --- helpers --------------------------------------------------------------

    private static MediaItem jpegItem(String id, String name, long size) {
        return MediaItem.newDiscovered(id, "cam", "primary", "DCIM", name, "/" + name, null, size);
    }

    private static CameraConnection connection() {
        return new CameraConnection() {
            @Override public String cameraId() { return "cam"; }
            @Override public String transport() { return "test"; }
            @Override public boolean isOpen() { return true; }
            @Override public void close() { /* no-op */ }
        };
    }

    /** Backend that lists, downloads N bytes and records delete invocations. */
    private static final class FakeBackend implements GenericCameraBackend {
        private final List<MediaItem> items;
        private final long writeBytes;
        private final AtomicBoolean deleteCalled;
        private final boolean deleteResult;
        FakeBackend(List<MediaItem> items, long writeBytes, AtomicBoolean deleteCalled, boolean deleteResult) {
            this.items = items; this.writeBytes = writeBytes; this.deleteCalled = deleteCalled; this.deleteResult = deleteResult;
        }
        @Override public CameraConnection connect() { return connection(); }
        @Override public DeviceInfo getDeviceInfo(CameraConnection c) { return DeviceInfo.minimal("cam", "test", "test"); }
        @Override public CapabilitySet getCapabilities(CameraConnection c) { return CapabilitySet.empty(); }
        @Override public StorageInfo getStorageInfo(CameraConnection c) { return StorageInfo.empty(); }
        @Override public BatteryInfo getBatteryInfo(CameraConnection c) { return BatteryInfo.unknown(); }
        @Override public LensInfo getLensInfo(CameraConnection c) { return LensInfo.none(); }
        @Override public List<MediaItem> listMedia(CameraConnection c) { return items; }
        @Override public TransferResult downloadMedia(CameraConnection c, MediaItem item, OutputStream sink) {
            try { for (long i = 0; i < writeBytes; i++) sink.write(0x42); }
            catch (IOException e) { return TransferResult.failed(item, e.getMessage()); }
            // verified=true means the backend itself certified the bytes; size match still rechecked by service.
            return TransferResult.ok(item, null, writeBytes, true);
        }
        @Override public boolean deleteMedia(CameraConnection c, MediaItem item) { deleteCalled.set(true); return deleteResult; }
        @Override public MediaItem captureStill(CameraConnection c) { throw new UnsupportedOperationException(); }
        @Override public void startLiveView(CameraConnection c) { }
        @Override public void stopLiveView(CameraConnection c) { }
        @Override public LiveViewFrame nextLiveViewFrame(CameraConnection c) { return null; }
    }
}
