package com.photographer.focusstack.cr3.metadata;

/**
 * A sealed interface representing a single CTMD (Canon Timed Metadata) record.
 */
public sealed interface CtmdRecord {
    int type();

    record TimestampRecord(int year, int month, int day,
                           int hour, int minute, int second, int centisecond)
            implements CtmdRecord {
        public int type() { return 1; }
    }

    record FocalLengthRecord(int numerator, int denominator) implements CtmdRecord {
        public int type() { return 4; }
        public double focalLengthMm() {
            return denominator > 0 ? (double) numerator / denominator : 0;
        }
    }

    record ExposureRecord(int fNumerator, int fDenominator,
                          int expNumerator, int expDenominator,
                          int isoSpeedRating) implements CtmdRecord {
        public int type() { return 5; }
        public double fNumber() {
            return fDenominator > 0 ? (double) fNumerator / fDenominator : 0;
        }
        public double exposureTime() {
            return expDenominator > 0 ? (double) expNumerator / expDenominator : 0;
        }
    }

    record UnknownRecord(int type, byte[] payload) implements CtmdRecord {
        public UnknownRecord {
            payload = payload == null ? null : payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload == null ? null : payload.clone();
        }
    }
}
