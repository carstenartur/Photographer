package com.photographer.focusstack.cr3.research;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Separates repeat-instability from stimulus-dependent payload changes.
 *
 * <p>For every compared bit, A1/A2 and B1/B2 must each agree before an A-vs-B
 * difference is classified as stable. A bit that changes inside either repeat
 * pair is classified as unstable and cannot support a spatial mapping.</p>
 */
final class StablePayloadDiff {

    record ByteRun(
            int startInclusive,
            int endExclusive,
            int changedBytes,
            int changedBits) {
        ByteRun {
            if (startInclusive < 0 || endExclusive <= startInclusive) {
                throw new IllegalArgumentException("Invalid byte run bounds");
            }
            if (changedBytes != endExclusive - startInclusive || changedBits < changedBytes) {
                throw new IllegalArgumentException("Invalid byte run counts");
            }
        }
    }

    record Result(
            int comparedBytes,
            int minimumInputLength,
            int maximumInputLength,
            boolean lengthMismatch,
            int stableChangedBytes,
            long stableChangedBits,
            int unstableBytes,
            long unstableBits,
            int firstStableChangedByte,
            List<ByteRun> stableRuns) {
        Result {
            stableRuns = List.copyOf(stableRuns);
        }

        boolean hasStableChange() {
            return firstStableChangedByte >= 0;
        }
    }

    private StablePayloadDiff() {
    }

    static Result compare(byte[] a1, byte[] a2, byte[] b1, byte[] b2) {
        Objects.requireNonNull(a1, "a1");
        Objects.requireNonNull(a2, "a2");
        Objects.requireNonNull(b1, "b1");
        Objects.requireNonNull(b2, "b2");

        int minimumLength = Math.min(Math.min(a1.length, a2.length), Math.min(b1.length, b2.length));
        int maximumLength = Math.max(Math.max(a1.length, a2.length), Math.max(b1.length, b2.length));
        boolean lengthMismatch = minimumLength != maximumLength;

        int stableChangedBytes = 0;
        long stableChangedBits = 0;
        int unstableBytes = 0;
        long unstableBits = 0;
        int firstStableChangedByte = -1;
        List<ByteRun> runs = new ArrayList<>();
        int runStart = -1;
        int runBits = 0;

        for (int index = 0; index < minimumLength; index++) {
            int aFirst = Byte.toUnsignedInt(a1[index]);
            int aSecond = Byte.toUnsignedInt(a2[index]);
            int bFirst = Byte.toUnsignedInt(b1[index]);
            int bSecond = Byte.toUnsignedInt(b2[index]);

            int unstableMask = (aFirst ^ aSecond) | (bFirst ^ bSecond);
            int stableChangeMask = (aFirst ^ bFirst) & ~unstableMask & 0xff;

            if (unstableMask != 0) {
                unstableBytes++;
                unstableBits += Integer.bitCount(unstableMask);
            }
            if (stableChangeMask != 0) {
                if (firstStableChangedByte < 0) {
                    firstStableChangedByte = index;
                }
                stableChangedBytes++;
                int changedBits = Integer.bitCount(stableChangeMask);
                stableChangedBits += changedBits;
                if (runStart < 0) {
                    runStart = index;
                    runBits = 0;
                }
                runBits += changedBits;
            } else if (runStart >= 0) {
                runs.add(new ByteRun(runStart, index, index - runStart, runBits));
                runStart = -1;
                runBits = 0;
            }
        }
        if (runStart >= 0) {
            runs.add(new ByteRun(runStart, minimumLength,
                    minimumLength - runStart, runBits));
        }

        return new Result(
                minimumLength,
                minimumLength,
                maximumLength,
                lengthMismatch,
                stableChangedBytes,
                stableChangedBits,
                unstableBytes,
                unstableBits,
                firstStableChangedByte,
                runs);
    }
}
