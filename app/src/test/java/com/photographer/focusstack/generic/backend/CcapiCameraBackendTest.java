package com.photographer.focusstack.generic.backend;

import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.camera.CameraState;
import com.photographer.focusstack.common.camera.CameraStatusProvider;
import com.photographer.focusstack.common.camera.Operation;
import com.photographer.focusstack.common.capability.Capability;
import com.photographer.focusstack.common.capability.CapabilitySet;
import com.photographer.focusstack.common.device.BatteryInfo;
import com.photographer.focusstack.common.device.StorageInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class CcapiCameraBackendTest {

    @Test
    void mapsConnectedCameraStateToBatteryAndPrimaryStorage() {
        CameraBackend legacy = mock(CameraBackend.class);
        when(legacy.getState()).thenReturn(state(true, 67, 1_234_567L));
        CcapiCameraBackend backend = new CcapiCameraBackend("camera-1", legacy);

        BatteryInfo battery = backend.getBatteryInfo(null);
        StorageInfo storage = backend.getStorageInfo(null);

        assertEquals(67, battery.percent());
        assertEquals("half", battery.level());
        assertEquals("unknown", battery.kind());
        assertEquals(1, storage.volumes().size());
        assertEquals("primary", storage.volumes().getFirst().name());
        assertEquals(1_234_567L, storage.volumes().getFirst().freeBytes());
        assertEquals(-1L, storage.volumes().getFirst().totalBytes());
    }

    @Test
    void prefersRichBatteryAndMultiVolumeStorageWithoutLegacyStateFallback() {
        CameraBackend legacy = richStatusBackendMock();
        CameraStatusProvider provider = (CameraStatusProvider) legacy;
        BatteryInfo battery = new BatteryInfo("quarter", "battery", "good", 25);
        StorageInfo storage = new StorageInfo(List.of(
                new StorageInfo.Volume("card1", "/contents/card1", 100L, 40L, 12, "readwrite"),
                new StorageInfo.Volume("card2", "/contents/card2", 200L, 75L, 20, "readonly")
        ));
        when(provider.getBatteryInfo()).thenReturn(battery);
        when(provider.getStorageInfo()).thenReturn(storage);
        CcapiCameraBackend backend = new CcapiCameraBackend("camera-1", legacy);

        assertEquals(battery, backend.getBatteryInfo(null));
        assertEquals(storage, backend.getStorageInfo(null));
        verify(legacy, never()).getState();
    }

    @Test
    void authoritativeUnknownRichStatusDoesNotFallBackToLegacyDefaults() {
        CameraBackend legacy = richStatusBackendMock();
        CameraStatusProvider provider = (CameraStatusProvider) legacy;
        when(provider.getBatteryInfo()).thenReturn(BatteryInfo.unknown());
        when(provider.getStorageInfo()).thenReturn(StorageInfo.empty());
        when(legacy.getState()).thenReturn(state(true, 100, 0L));
        CcapiCameraBackend backend = new CcapiCameraBackend("camera-1", legacy);

        assertEquals(BatteryInfo.unknown(), backend.getBatteryInfo(null));
        assertTrue(backend.getStorageInfo(null).volumes().isEmpty());
        verify(legacy, never()).getState();
    }

    @Test
    void preservesKnownZeroValues() {
        CameraState empty = state(true, 0, 0L);

        BatteryInfo battery = CcapiCameraBackend.toBatteryInfo(empty);
        StorageInfo storage = CcapiCameraBackend.toStorageInfo(empty);

        assertEquals(0, battery.percent());
        assertEquals("empty", battery.level());
        assertEquals(0L, storage.totalFreeBytes());
    }

    @Test
    void disconnectedStateDoesNotBecomeMisleadingStatus() {
        CameraState disconnected = state(false, 100, 0L);

        assertEquals(BatteryInfo.unknown(), CcapiCameraBackend.toBatteryInfo(disconnected));
        assertTrue(CcapiCameraBackend.toStorageInfo(disconnected).volumes().isEmpty());
    }

    @Test
    void unknownSentinelsRemainUnknown() {
        CameraState unknown = state(true, -1, -1L);

        assertEquals(BatteryInfo.unknown(), CcapiCameraBackend.toBatteryInfo(unknown));
        assertTrue(CcapiCameraBackend.toStorageInfo(unknown).volumes().isEmpty());
    }

    @Test
    void capabilitiesIncludeOnlyKnownConnectedStatus() {
        CameraBackend legacy = mock(CameraBackend.class);
        when(legacy.supports(any(Operation.class))).thenReturn(false);
        when(legacy.getState()).thenReturn(state(true, 80, 42L));
        CcapiCameraBackend backend = new CcapiCameraBackend("camera-1", legacy);

        CapabilitySet capabilities = backend.getCapabilities(null);

        assertTrue(capabilities.has(Capability.DEVICE_INFO));
        assertTrue(capabilities.has(Capability.BATTERY_INFO));
        assertTrue(capabilities.has(Capability.STORAGE_INFO));
        assertFalse(capabilities.has(Capability.LENS_INFO));
        verify(legacy).getState();
    }

    @Test
    void richStatusCapabilitiesUseOneAuthoritativeSnapshotWithoutLegacyFallback() {
        CameraBackend legacy = richStatusBackendMock();
        CameraStatusProvider provider = (CameraStatusProvider) legacy;
        when(legacy.supports(any(Operation.class))).thenReturn(false);
        when(provider.getBatteryInfo()).thenReturn(
                new BatteryInfo("high", "battery", "good", 80));
        when(provider.getStorageInfo()).thenReturn(new StorageInfo(List.of(
                new StorageInfo.Volume("card1", "/contents/card1", 100L, 42L, 1, "readwrite")
        )));
        CcapiCameraBackend backend = new CcapiCameraBackend("camera-1", legacy);

        CapabilitySet capabilities = backend.getCapabilities(null);

        assertTrue(capabilities.has(Capability.BATTERY_INFO));
        assertTrue(capabilities.has(Capability.STORAGE_INFO));
        verify(provider).getBatteryInfo();
        verify(provider).getStorageInfo();
        verify(legacy, never()).getState();
    }

    @Test
    void statusReadFailureDegradesToUnknownAndEmpty() {
        CameraBackend legacy = mock(CameraBackend.class);
        when(legacy.getState()).thenThrow(new IllegalStateException("camera offline"));
        CcapiCameraBackend backend = new CcapiCameraBackend("camera-1", legacy);

        assertEquals(BatteryInfo.unknown(), backend.getBatteryInfo(null));
        assertTrue(backend.getStorageInfo(null).volumes().isEmpty());
    }

    private static CameraBackend richStatusBackendMock() {
        return mock(CameraBackend.class,
                withSettings().extraInterfaces(CameraStatusProvider.class));
    }

    private static CameraState state(boolean connected, int batteryLevel, long storageAvailable) {
        return new CameraState(
                connected,
                batteryLevel,
                storageAvailable,
                0,
                "unknown",
                "unknown",
                "unknown",
                false
        );
    }
}
