package com.photographer.focusstack.cr3.metadata;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CtmdParserTest {

    private static byte[] buildCtmdRecord(short type, byte[] payload) {
        int recordSize = 12 + payload.length;
        ByteBuffer buf = ByteBuffer.allocate(recordSize).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(recordSize);
        buf.putShort(type);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.putShort((short) 1);
        buf.putShort((short) 0);
        buf.put(payload);
        return buf.array();
    }

    private static byte[] buildTimestampPayload(int year, int month, int day,
                                                 int hour, int minute, int second, int centisecond) {
        ByteBuffer buf = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 0);
        buf.putShort((short) year);
        buf.put((byte) month);
        buf.put((byte) day);
        buf.put((byte) hour);
        buf.put((byte) minute);
        buf.put((byte) second);
        buf.put((byte) centisecond);
        return buf.array();
    }

    private static byte[] buildFocalLengthPayload(int numerator, int denominator) {
        ByteBuffer buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) numerator);
        buf.putShort((short) denominator);
        return buf.array();
    }

    private static byte[] buildExposurePayload(int fNum, int fDen, int expNum, int expDen, int iso) {
        ByteBuffer buf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) fNum);
        buf.putShort((short) fDen);
        buf.putShort((short) expNum);
        buf.putShort((short) expDen);
        buf.putInt(iso);
        return buf.array();
    }

    @Test
    void parseEmptyData() {
        assertThat(CtmdParser.parse(new byte[0])).isEmpty();
    }

    @Test
    void parseTimestampRecord() {
        byte[] data = buildCtmdRecord((short) 1,
                buildTimestampPayload(2023, 6, 15, 12, 30, 45, 50));
        List<CtmdRecord> records = CtmdParser.parse(data);
        assertThat(records).hasSize(1);
        CtmdRecord.TimestampRecord ts = (CtmdRecord.TimestampRecord) records.getFirst();
        assertThat(ts.year()).isEqualTo(2023);
        assertThat(ts.month()).isEqualTo(6);
        assertThat(ts.day()).isEqualTo(15);
        assertThat(ts.hour()).isEqualTo(12);
        assertThat(ts.minute()).isEqualTo(30);
        assertThat(ts.second()).isEqualTo(45);
        assertThat(ts.centisecond()).isEqualTo(50);
        assertThat(ts.type()).isEqualTo(1);
    }

    @Test
    void parseFocalLengthRecord() {
        List<CtmdRecord> records = CtmdParser.parse(
                buildCtmdRecord((short) 4, buildFocalLengthPayload(500, 10)));
        CtmdRecord.FocalLengthRecord focal = (CtmdRecord.FocalLengthRecord) records.getFirst();
        assertThat(focal.focalLengthMm()).isEqualTo(50.0);
        assertThat(focal.type()).isEqualTo(4);
    }

    @Test
    void parseExposureRecord() {
        List<CtmdRecord> records = CtmdParser.parse(
                buildCtmdRecord((short) 5, buildExposurePayload(28, 10, 1, 250, 800)));
        CtmdRecord.ExposureRecord exposure = (CtmdRecord.ExposureRecord) records.getFirst();
        assertThat(exposure.fNumber()).isEqualTo(2.8, org.assertj.core.data.Offset.offset(0.001));
        assertThat(exposure.exposureTime()).isEqualTo(1.0 / 250,
                org.assertj.core.data.Offset.offset(0.000001));
        assertThat(exposure.isoSpeedRating()).isEqualTo(800);
    }

    @Test
    void parseMultipleRecords() {
        byte[] timestamp = buildCtmdRecord((short) 1,
                buildTimestampPayload(2023, 1, 1, 0, 0, 0, 0));
        byte[] focal = buildCtmdRecord((short) 4, buildFocalLengthPayload(500, 10));
        byte[] combined = new byte[timestamp.length + focal.length];
        System.arraycopy(timestamp, 0, combined, 0, timestamp.length);
        System.arraycopy(focal, 0, combined, timestamp.length, focal.length);
        assertThat(CtmdParser.parse(combined)).hasSize(2);
    }

    @Test
    void parseUnknownRecordTypeAndDefensivelyCopyPayload() {
        byte[] original = new byte[]{1, 2, 3};
        CtmdRecord.UnknownRecord record = new CtmdRecord.UnknownRecord(99, original);
        original[0] = 99;
        assertThat(record.payload()[0]).isEqualTo((byte) 1);
        byte[] returned = record.payload();
        returned[0] = 77;
        assertThat(record.payload()[0]).isEqualTo((byte) 1);
    }
}
