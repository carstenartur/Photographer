package com.photographer.focusstack.common.device;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StorageInfoTest {

    @Test
    void sumsKnownVolumesAndIgnoresUnknownValues() {
        StorageInfo storage = new StorageInfo(List.of(
                new StorageInfo.Volume("card1", null, 100L, 40L, 1, "readwrite"),
                new StorageInfo.Volume("card2", null, -1L, -1L, -1, "unknown"),
                new StorageInfo.Volume("card3", null, 200L, 75L, 2, "readonly")
        ));

        assertEquals(115L, storage.totalFreeBytes());
    }

    @Test
    void returnsUnknownWhenNoVolumeReportsFreeSpace() {
        StorageInfo storage = new StorageInfo(List.of(
                new StorageInfo.Volume("card1", null, -1L, -1L, -1, "unknown")
        ));

        assertEquals(-1L, storage.totalFreeBytes());
    }

    @Test
    void saturatesInsteadOfOverflowing() {
        StorageInfo storage = new StorageInfo(List.of(
                new StorageInfo.Volume("card1", null, Long.MAX_VALUE, Long.MAX_VALUE, 1, "readwrite"),
                new StorageInfo.Volume("card2", null, 1L, 1L, 1, "readwrite")
        ));

        assertEquals(Long.MAX_VALUE, storage.totalFreeBytes());
    }
}
