package com.photographer.focusstack.cr3.raw.codec;

import java.io.IOException;

/** Rice-Golomb entropy decoder for Canon CRX residual symbols. */
public final class RiceGolombDecoder {

    private final BitReader reader;
    private int riceParam;

    public RiceGolombDecoder(BitReader reader, int riceParam) {
        this.reader = reader;
        this.riceParam = riceParam;
    }

    public int decode() throws IOException {
        int quotient = reader.readUnary();
        int remainder = riceParam > 0 ? reader.readBits(riceParam) : 0;
        return (quotient << riceParam) | remainder;
    }

    public int decodeSigned() throws IOException {
        int unsigned = decode();
        if ((unsigned & 1) == 0) {
            return unsigned >> 1;
        }
        return -(unsigned >> 1) - 1;
    }

    public int riceParam() { return riceParam; }
    public void setRiceParam(int k) { this.riceParam = k; }
}
