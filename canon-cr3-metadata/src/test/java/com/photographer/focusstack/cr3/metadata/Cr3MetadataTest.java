package com.photographer.focusstack.cr3.metadata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link Cr3Metadata}.
 */
class Cr3MetadataTest {

    @Test
    @DisplayName("visibleWidth should fall back to sensor width when active area is not set")
    void visibleWidthFallback() {
        Cr3Metadata meta = new Cr3Metadata();
        meta.setSensorWidth(5472);
        assertThat(meta.visibleWidth()).isEqualTo(5472);
    }

    @Test
    @DisplayName("visibleWidth should use active area when set")
    void visibleWidthActive() {
        Cr3Metadata meta = new Cr3Metadata();
        meta.setSensorWidth(5504);
        meta.setActiveAreaLeft(100);
        meta.setActiveAreaRight(5500);
        assertThat(meta.visibleWidth()).isEqualTo(5400);
    }

    @Test
    @DisplayName("visibleHeight should fall back to sensor height when active area is not set")
    void visibleHeightFallback() {
        Cr3Metadata meta = new Cr3Metadata();
        meta.setSensorHeight(3648);
        assertThat(meta.visibleHeight()).isEqualTo(3648);
    }

    @Test
    @DisplayName("visibleHeight should use active area when set")
    void visibleHeightActive() {
        Cr3Metadata meta = new Cr3Metadata();
        meta.setSensorHeight(3700);
        meta.setActiveAreaTop(50);
        meta.setActiveAreaBottom(3698);
        assertThat(meta.visibleHeight()).isEqualTo(3648);
    }

    @Test
    @DisplayName("Default bit depth should be 14")
    void defaultBitDepth() {
        Cr3Metadata meta = new Cr3Metadata();
        assertThat(meta.bitDepth()).isEqualTo(14);
    }
}
