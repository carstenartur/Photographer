package com.photographer.focusstack.cr3.metadata;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Parses little-endian Canon Timed Metadata record blocks. */
public final class CtmdParser {

    private CtmdParser() {}

    public static List<CtmdRecord> parse(byte[] data) {
        List<CtmdRecord> records = new ArrayList<>();
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        while (buf.remaining() >= 12) {
            int recordSize = buf.getInt();
            if (recordSize < 12 || recordSize > buf.capacity()) {
                break;
            }
            short type = buf.getShort();
            buf.get();
            buf.get();
            buf.getShort();
            buf.getShort();

            int payloadLen = recordSize - 12;
            byte[] payload = new byte[Math.max(0, payloadLen)];
            int toRead = Math.min(payload.length, buf.remaining());
            buf.get(payload, 0, toRead);
            if (toRead < payloadLen) {
                break;
            }

            records.add(parseRecord(type, payload));
        }

        return Collections.unmodifiableList(records);
    }

    private static CtmdRecord parseRecord(short type, byte[] payload) {
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        return switch (type) {
            case 1 -> parseTimestamp(buf, payload);
            case 4 -> parseFocalLength(buf, payload);
            case 5 -> parseExposure(buf, payload);
            default -> new CtmdRecord.UnknownRecord(type, Arrays.copyOf(payload, payload.length));
        };
    }

    private static CtmdRecord parseTimestamp(ByteBuffer buf, byte[] payload) {
        if (payload.length < 10) {
            return new CtmdRecord.UnknownRecord(1, payload);
        }
        buf.getShort();
        int year = buf.getShort() & 0xFFFF;
        int month = buf.get() & 0xFF;
        int day = buf.get() & 0xFF;
        int hour = buf.get() & 0xFF;
        int minute = buf.get() & 0xFF;
        int second = buf.get() & 0xFF;
        int centisecond = buf.get() & 0xFF;
        return new CtmdRecord.TimestampRecord(year, month, day, hour, minute, second, centisecond);
    }

    private static CtmdRecord parseFocalLength(ByteBuffer buf, byte[] payload) {
        if (payload.length < 4) {
            return new CtmdRecord.UnknownRecord(4, payload);
        }
        int numerator = buf.getShort() & 0xFFFF;
        int denominator = buf.getShort() & 0xFFFF;
        return new CtmdRecord.FocalLengthRecord(numerator, denominator);
    }

    private static CtmdRecord parseExposure(ByteBuffer buf, byte[] payload) {
        if (payload.length < 12) {
            return new CtmdRecord.UnknownRecord(5, payload);
        }
        int fNum = buf.getShort() & 0xFFFF;
        int fDen = buf.getShort() & 0xFFFF;
        int expNum = buf.getShort() & 0xFFFF;
        int expDen = buf.getShort() & 0xFFFF;
        int iso = buf.getInt();
        return new CtmdRecord.ExposureRecord(fNum, fDen, expNum, expDen, iso);
    }
}
