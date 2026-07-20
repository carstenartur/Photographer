package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.raw.codec.BitReader;

import java.io.IOException;

/** D8 test-only decoder: contract K on S=0, bracket only non-zero symbols. */
final class ZeroContractedBracketDecoder {
    record Result(int[] pixels, int symbolsDecoded, boolean exhausted) {}

    private ZeroContractedBracketDecoder() {}

    static Result decode(byte[] payload, int width, int height, int bitDepth) {
        int[] pixels = new int[width * height];
        int max = (1 << bitDepth) - 1;
        BitReader bits = new BitReader(payload);
        int symbols = 0;
        boolean exhausted = false;

        outer:
        for (int y = 0; y < height; y++) {
            int k = 1;
            for (int x = 0; x < width; x++) {
                int predictor = predictor(pixels, width, x, y);
                int unsigned;
                try {
                    unsigned = read(bits, k);
                } catch (IOException | UnaryOverflow failure) {
                    exhausted = true;
                    int value = clamp(predictor, max);
                    pixels[y * width + x] = value;
                    pad(pixels, width, height, x, y, value);
                    break outer;
                }
                int magnitude = unsigned >>> 1;
                int signed = (unsigned & 1) == 0 ? magnitude : -magnitude;
                pixels[y * width + x] = clamp(predictor + signed, max);
                k = nextK(k, unsigned);
                symbols++;
            }
        }
        return new Result(pixels, symbols, exhausted);
    }

    static int nextK(int currentK, int symbol) {
        if (currentK < 0 || currentK > CrxNormativeLosslessCandidate.K_MAX) {
            throw new IllegalArgumentException("currentK outside candidate clamp");
        }
        if (symbol < 0) {
            throw new IllegalArgumentException("symbol must be non-negative");
        }
        if (symbol == 0 && currentK > 0) {
            return currentK - 1;
        }
        return CrxNormativeLosslessCandidate.symbolBracketNextK(currentK, symbol);
    }

    private static int read(BitReader bits, int k) throws IOException, UnaryOverflow {
        int quotient = 0;
        while (bits.readBit() == 1) {
            quotient++;
            if (quotient > 32) {
                throw new UnaryOverflow();
            }
        }
        int remainder = k == 0 ? 0 : bits.readBits(k);
        return (quotient << k) | remainder;
    }

    private static int predictor(int[] pixels, int width, int x, int y) {
        if (x == 0 && y == 0) {
            return 0;
        }
        if (y == 0) {
            return pixels[x - 1];
        }
        if (x == 0) {
            return pixels[(y - 1) * width];
        }
        int west = pixels[y * width + x - 1];
        int north = pixels[(y - 1) * width + x];
        int northWest = pixels[(y - 1) * width + x - 1];
        if (northWest >= Math.max(west, north)) {
            return Math.min(west, north);
        }
        if (northWest <= Math.min(west, north)) {
            return Math.max(west, north);
        }
        return west + north - northWest;
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(value, max));
    }

    private static void pad(int[] pixels, int width, int height,
                            int x, int y, int value) {
        for (int xx = x + 1; xx < width; xx++) {
            pixels[y * width + xx] = value;
        }
        for (int yy = y + 1; yy < height; yy++) {
            for (int xx = 0; xx < width; xx++) {
                pixels[yy * width + xx] = value;
            }
        }
    }

    @SuppressWarnings("serial")
    private static final class UnaryOverflow extends Exception {
        UnaryOverflow() {
            super(null, null, false, false);
        }
    }
}
