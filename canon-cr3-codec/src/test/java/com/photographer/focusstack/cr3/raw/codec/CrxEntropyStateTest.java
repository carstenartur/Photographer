package com.photographer.focusstack.cr3.raw.codec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CrxEntropyStateTest {
    @Test
    void predictionWidthChangesAtTheExactThresholds() {
        for (int k = 0; k <= CrxEntropyState.MAX_RICE_WIDTH; k++) {
            int scale = 1 << k;
            int lastDecrease = (scale - 1) / 2;
            int wider = Math.min(CrxEntropyState.MAX_RICE_WIDTH, k + 1);
            int widest = Math.min(CrxEntropyState.MAX_RICE_WIDTH, k + 2);

            assertEquals(Math.max(0, k - 1), CrxEntropyState.adaptK(k, lastDecrease));
            assertEquals(k, CrxEntropyState.adaptK(k, lastDecrease + 1));
            assertEquals(k, CrxEntropyState.adaptK(k, 3 * scale - 1));
            assertEquals(wider, CrxEntropyState.adaptK(k, 3 * scale));
            assertEquals(wider, CrxEntropyState.adaptK(k, 6 * scale - 1));
            assertEquals(widest, CrxEntropyState.adaptK(k, 6 * scale));
            assertEquals(widest, CrxEntropyState.adaptK(k, 3 * (1 << 20) - 2));
        }
    }
}
