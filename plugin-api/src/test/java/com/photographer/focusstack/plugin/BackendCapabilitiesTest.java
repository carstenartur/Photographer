package com.photographer.focusstack.plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BackendCapabilitiesTest {

    @Test
    void none_allFlagsFalse() {
        BackendCapabilities c = BackendCapabilities.none();
        assertFalse(c.supportsFocusDrive());
        assertFalse(c.supportsContentsListing());
        assertFalse(c.supportsDeleteAfterDownload());
        assertFalse(c.supportsVideoCapture());
        assertFalse(c.supportsLocalVisionAnalysis());
        assertFalse(c.supportsUSB());
        assertFalse(c.supportsWiFi());
    }

    @Test
    void all_allFlagsTrue() {
        BackendCapabilities c = BackendCapabilities.all();
        assertTrue(c.supportsFocusDrive());
        assertTrue(c.supportsContentsListing());
        assertTrue(c.supportsDeleteAfterDownload());
        assertTrue(c.supportsVideoCapture());
        assertTrue(c.supportsLocalVisionAnalysis());
        assertTrue(c.supportsUSB());
        assertTrue(c.supportsWiFi());
    }

    @Test
    void builder_setsIndividualFlags() {
        BackendCapabilities c = BackendCapabilities.builder()
                .focusDrive(true)
                .deleteAfterDownload(true)
                .wifi(true)
                .build();
        assertTrue(c.supportsFocusDrive());
        assertTrue(c.supportsDeleteAfterDownload());
        assertTrue(c.supportsWiFi());
        assertFalse(c.supportsContentsListing());
        assertFalse(c.supportsVideoCapture());
        assertFalse(c.supportsLocalVisionAnalysis());
        assertFalse(c.supportsUSB());
    }

    @Test
    void builder_emptyBuildEqualsNone() {
        assertEquals(BackendCapabilities.none(), BackendCapabilities.builder().build());
    }

    @Test
    void cameraBackendPlugin_defaultCapabilitiesIsNone() {
        // Verify the default method on CameraBackendPlugin returns none() so that
        // existing plugins keep compiling without changes.
        CameraBackendPlugin plugin = new CameraBackendPlugin() {
            @Override public PluginMetadata metadata() {
                return PluginMetadata.of("test", "Test", "0.1");
            }
            @Override public String backendType() { return "test"; }
            @Override public com.photographer.focusstack.common.camera.CameraInfo getInfo() { return null; }
            @Override public com.photographer.focusstack.common.camera.LiveViewFrame getLiveView() { return null; }
            @Override public com.photographer.focusstack.common.camera.CaptureResult capture() { return null; }
            @Override public void setFocus(com.photographer.focusstack.common.camera.FocusCommand c) { }
            @Override public com.photographer.focusstack.common.camera.CameraState getState() { return null; }
            @Override public boolean supports(com.photographer.focusstack.common.camera.Operation o) { return false; }
        };
        assertEquals(BackendCapabilities.none(), plugin.capabilities());
    }
}
