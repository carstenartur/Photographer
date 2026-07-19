package com.photographer.focusstack.cr3.raw.codec;

import java.util.Objects;

/** Maps an unsigned entropy symbol to a signed prediction residual. */
final class CrxResidualSignMapper {

    enum Mapping {
        /** Magnitude in bits 1..n, sign in bit 0; odd zero maps to negative zero (= 0). */
        BIT0_SIGN_MAGNITUDE,
        /** Conventional zig-zag mapping: 0, -1, +1, -2, +2, ... */
        ZIG_ZAG
    }

    private CrxResidualSignMapper() {
    }

    static Mapping forRule(CrxLosslessRawDecoder.KUpdateRule rule) {
        Objects.requireNonNull(rule, "rule");
        return rule == CrxLosslessRawDecoder.KUpdateRule.PATENT_BRACKET
                ? Mapping.BIT0_SIGN_MAGNITUDE
                : Mapping.ZIG_ZAG;
    }

    static int decode(int encodedResidual, Mapping mapping) {
        if (encodedResidual < 0) {
            throw new IllegalArgumentException("encodedResidual must be non-negative");
        }
        Objects.requireNonNull(mapping, "mapping");
        return switch (mapping) {
            case BIT0_SIGN_MAGNITUDE -> {
                int magnitude = encodedResidual >>> 1;
                yield (encodedResidual & 1) == 0 ? magnitude : -magnitude;
            }
            case ZIG_ZAG -> (encodedResidual & 1) == 0
                    ? encodedResidual >>> 1
                    : -(encodedResidual >>> 1) - 1;
        };
    }
}
