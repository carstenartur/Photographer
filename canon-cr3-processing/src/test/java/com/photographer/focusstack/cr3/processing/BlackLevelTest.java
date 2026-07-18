package com.photographer.focusstack.cr3.processing;

import com.photographer.focusstack.cr3.raw.Cr3RawImage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link BlackLevel}.
 */
class BlackLevelTest {

    @Test
    @DisplayName("Global black level returns same value for all positions")
    void globalLevel() {
        BlackLevel bl = BlackLevel.global(512);
        assertThat(bl.isPerChannel()).isFalse();
        assertThat(bl.globalLevel()).isEqualTo(512);
        assertThat(bl.levelAt(0, 0, Cr3RawImage.BayerPattern.RGGB)).isEqualTo(512);
        assertThat(bl.levelAt(1, 1, Cr3RawImage.BayerPattern.RGGB)).isEqualTo(512);
    }

    @Test
    @DisplayName("Per-channel black level returns correct value for each Bayer position (RGGB)")
    void perChannelRGGB() {
        // [R=500, Gr=510, Gb=520, B=530]
        BlackLevel bl = BlackLevel.perChannel(500, 510, 520, 530);
        assertThat(bl.isPerChannel()).isTrue();

        // RGGB: (0,0)=R, (1,0)=Gr, (0,1)=Gb, (1,1)=B
        assertThat(bl.levelAt(0, 0, Cr3RawImage.BayerPattern.RGGB)).isEqualTo(500); // R
        assertThat(bl.levelAt(1, 0, Cr3RawImage.BayerPattern.RGGB)).isEqualTo(510); // Gr
        assertThat(bl.levelAt(0, 1, Cr3RawImage.BayerPattern.RGGB)).isEqualTo(520); // Gb
        assertThat(bl.levelAt(1, 1, Cr3RawImage.BayerPattern.RGGB)).isEqualTo(530); // B
    }

    @Test
    @DisplayName("Per-channel black level works with BGGR pattern")
    void perChannelBGGR() {
        BlackLevel bl = BlackLevel.perChannel(500, 510, 520, 530);

        // BGGR: (0,0)=B, (1,0)=Gb, (0,1)=Gr, (1,1)=R
        assertThat(bl.levelAt(0, 0, Cr3RawImage.BayerPattern.BGGR)).isEqualTo(530); // B
        assertThat(bl.levelAt(1, 0, Cr3RawImage.BayerPattern.BGGR)).isEqualTo(520); // Gb
        assertThat(bl.levelAt(0, 1, Cr3RawImage.BayerPattern.BGGR)).isEqualTo(510); // Gr
        assertThat(bl.levelAt(1, 1, Cr3RawImage.BayerPattern.BGGR)).isEqualTo(500); // R
    }

    @Test
    @DisplayName("Default level is 512")
    void defaultLevel() {
        BlackLevel bl = BlackLevel.defaultLevel();
        assertThat(bl.globalLevel()).isEqualTo(512);
    }

    @Test
    @DisplayName("Per-channel from array requires exactly 4 values")
    void perChannelArrayValidation() {
        assertThatThrownBy(() -> BlackLevel.perChannel(new int[]{1, 2, 3}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("perChannelLevels returns a copy")
    void perChannelCopy() {
        BlackLevel bl = BlackLevel.perChannel(100, 200, 300, 400);
        int[] levels = bl.perChannelLevels();
        levels[0] = 999;
        assertThat(bl.perChannelLevels()[0]).isEqualTo(100);
    }

    @Test
    @DisplayName("toString for global shows value")
    void toStringGlobal() {
        assertThat(BlackLevel.global(512).toString()).contains("global=512");
    }

    @Test
    @DisplayName("toString for per-channel shows all values")
    void toStringPerChannel() {
        String s = BlackLevel.perChannel(1, 2, 3, 4).toString();
        assertThat(s).contains("perChannel").contains("R:1").contains("B:4");
    }
}
