package com.photographer.focusstack.cr3.raw.codec;

import java.util.Objects;

/** Mutable per-plane state for CRX adaptive Rice parameter updates. */
final class CrxRiceAdaptationState {

    private static final int NORMALIZATION_THRESHOLD = 1024;
    private static final int PATENT_LOSSLESS_K_MAX = 12;

    private final CrxLosslessRawDecoder.KUpdateRule rule;
    private final int bitDepth;
    private int k;
    private long accumulator;
    private long sampleCount;

    CrxRiceAdaptationState(CrxLosslessRawDecoder.KUpdateRule rule, int bitDepth) {
        this.rule = Objects.requireNonNull(rule, "rule");
        if (bitDepth <= 0 || bitDepth > 16) {
            throw new IllegalArgumentException("Unsupported bit depth: " + bitDepth);
        }
        this.bitDepth = bitDepth;
        resetForPlane();
    }

    int k() {
        return k;
    }

    long accumulator() {
        return accumulator;
    }

    long sampleCount() {
        return sampleCount;
    }

    /** Starts a new decoded row. PATENT_BRACKET is row-local; JPEG_LS is plane-local. */
    void startRow() {
        if (rule == CrxLosslessRawDecoder.KUpdateRule.PATENT_BRACKET) {
            resetPatentRow();
        }
    }

    void update(int signedResidual) {
        int absoluteResidual = Math.abs(signedResidual);
        accumulator += absoluteResidual;
        sampleCount++;

        switch (rule) {
            case JPEG_LS -> updateJpegLs();
            case PATENT_BRACKET -> updatePatentBracket(absoluteResidual);
        }
        normalizeIfRequired();
    }

    private void resetForPlane() {
        k = rule == CrxLosslessRawDecoder.KUpdateRule.PATENT_BRACKET ? 1 : 0;
        accumulator = 0;
        sampleCount = 1;
    }

    private void resetPatentRow() {
        k = 1;
        accumulator = 0;
        sampleCount = 1;
    }

    private void updateJpegLs() {
        long upperThreshold = sampleCount << k;
        if (accumulator > upperThreshold && k < bitDepth) {
            k++;
        } else if (k > 0) {
            long lowerThreshold = sampleCount << (k - 1);
            if (accumulator <= lowerThreshold) {
                k--;
            }
        }
    }

    private void updatePatentBracket(int absoluteResidual) {
        if (absoluteResidual > (1 << (k + 1)) && k < PATENT_LOSSLESS_K_MAX) {
            k++;
        } else if (k > 0 && accumulator <= (sampleCount << (k - 1))) {
            k--;
        }
    }

    private void normalizeIfRequired() {
        if (sampleCount >= NORMALIZATION_THRESHOLD) {
            accumulator >>= 1;
            sampleCount >>= 1;
            if (sampleCount == 0) {
                sampleCount = 1;
            }
        }
    }
}
