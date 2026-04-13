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
        buf.put((byte) 0); // tiffFlag
        buf.put((byte) 0); // tiffFlag2
        buf.putShort((short) 1); // always 1
        buf.putShort((short) 0); // unknown
        buf.put(payload);
        return buf.array();
    }

    private static byte[] buildTimestampPayload(int year, int month, int day,
                                                 int hour, int minute, int second, int centisecond) {
        ByteBuffer buf = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 0); // unknown
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
        List<CtmdRecord> records = CtmdParser.parse(new byte[0]);
        assertThat(records).isEmpty();
    }

    @Test
    void parseTimestampRecord() {
        byte[] payload = buildTimestampPayload(2023, 6, 15, 12, 30, 45, 50);
        byte[] data = buildCtmdRecord((short) 1, payload);

        List<CtmdRecord> records = CtmdParser.parse(data);

        assertThat(records).hasSize(1);
        assertThat(records.get(0)).isInstanceOf(CtmdRecord.TimestampRecord.class);
        CtmdRecord.TimestampRecord ts = (CtmdRecord.TimestampRecord) records.get(0);
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
        byte[] payload = buildFocalLengthPayload(500, 10); // 50mm
        byte[] data = buildCtmdRecord((short) 4, payload);

        List<CtmdRecord> records = CtmdParser.parse(data);

        assertThat(records).hasSize(1);
        CtmdRecord.FocalLengthRecord fl = (CtmdRecord.FocalLengthRecord) records.get(0);
        assertThat(fl.numerator()).isEqualTo(500);
        assertThat(fl.denominator()).isEqualTo(10);
        assertThat(fl.focalLengthMm()).isEqualTo(50.0);
        assertThat(fl.type()).isEqualTo(4);
    }

    @Test
    void parseExposureRecord() {
        byte[] payload = buildExposurePayload(28, 10, 1, 250, 800); // f/2.8, 1/250s, ISO 800
        byte[] data = buildCtmdRecord((short) 5, payload);

        List<CtmdRecord> records = CtmdParser.parse(data);

        assertThat(records).hasSize(1);
        CtmdRecord.ExposureRecord exp = (CtmdRecord.ExposureRecord) records.get(0);
        assertThat(exp.fNumber()).isEqualTo(2.8, org.assertj.core.data.Offset.offset(0.001));
        assertThat(exp.exposureTime()).isEqualTo(1.0 / 250, org.assertj.core.data.Offset.offset(0.000001));
        assertThat(exp.isoSpeedRating()).isEqualTo(800);
        assertThat(exp.type()).isEqualTo(5);
    }

    @Test
    void parseMultipleRecords() {
        byte[] ts = buildCtmdRecord((short) 1, buildTimestampPayload(2023, 1, 1, 0, 0, 0, 0));
        byte[] fl = buildCtmdRecord((short) 4, buildFocalLengthPayload(500, 10));
        byte[] combined = new byte[ts.length + fl.length];
        System.arraycopy(ts, 0, combined, 0, ts.length);
        System.arraycopy(fl, 0, combined, ts.length, fl.length);

        List<CtmdRecord> records = CtmdParser.parse(combined);

        assertThat(records).hasSize(2);
        assertThat(records.get(0)).isInstanceOf(CtmdRecord.TimestampRecord.class);
        assertThat(records.get(1)).isInstanceOf(CtmdRecord.FocalLengthRecord.class);
    }

    @Test
    void parseUnknownRecordType() {
        byte[] data = buildCtmdRecord((short) 99, new byte[]{1, 2, 3});

        List<CtmdRecord> records = CtmdParser.parse(data);

        assertThat(records).hasSize(1);
        assertThat(records.get(0)).isInstanceOf(CtmdRecord.UnknownRecord.class);
        assertThat(records.get(0).type()).isEqualTo(99);
    }

    @Test
    void zeroDenominatorFocalLength() {
        byte[] payload = buildFocalLengthPayload(500, 0);
        byte[] data = buildCtmdRecord((short) 4, payload);

        List<CtmdRecord> records = CtmdParser.parse(data);

        CtmdRecord.FocalLengthRecord fl = (CtmdRecord.FocalLengthRecord) records.get(0);
        assertThat(fl.focalLengthMm()).isEqualTo(0.0);
    }
}
