package com.photographer.focusstack.cr3.decode;

import com.photographer.focusstack.cr3.raw.codec.CrxLosslessEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferUShort;
import java.awt.image.Raster;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

import static com.photographer.focusstack.cr3.testutil.Cr3TestData.buildBox;
import static com.photographer.focusstack.cr3.testutil.Cr3TestData.buildCr3FtypBox;
import static com.photographer.focusstack.cr3.testutil.Cr3TestData.concat;
import static org.junit.jupiter.api.Assertions.*;

/** Runs the real import in a separate heap; the expected image never lives in that heap. */
class Cr3LowMemoryImportTest {
    @TempDir Path directory;

    @Test
    void automaticallyLoadsRotatedRgb16WhenOnlyTheFinalImageFits() throws Exception {
        Path source = directory.resolve("rotated.cr3");
        Files.write(source, sensorFile());
        Result reference = read(source, 512, Cr3DecodeOptions.MemoryMode.FAST);
        assertEquals(0, reference.exit(), reference.error());
        assertFalse(reference.error().contains("CR3 low-memory"), reference.error());

        Result limited = read(source, 128, Cr3DecodeOptions.MemoryMode.AUTO);
        assertEquals(0, limited.exit(), limited.error());
        var expected = reference.output().lines().filter(line -> line.startsWith("CR3_RESULT ")).toList();
        var actual = limited.output().lines().filter(line -> line.startsWith("CR3_RESULT ")).toList();
        assertEquals(1, expected.size());
        assertEquals(expected, actual, "All numeric samples and the output type must agree");
        assertEquals(1, limited.error().lines().filter(line -> line.contains("CR3 low-memory")).count(),
                limited.error());
        Result fastLimited = read(source, 128, Cr3DecodeOptions.MemoryMode.FAST);
        assertNotEquals(0, fastLimited.exit(), "The old allocation layout must exceed this heap");
        assertTrue(fastLimited.error().contains("OutOfMemoryError"), fastLimited.error());
        assertFalse(fastLimited.error().contains("CR3 low-memory"), fastLimited.error());
    }

    private Result read(Path source, int heapMiB, Cr3DecodeOptions.MemoryMode mode) throws Exception {
        Path out = directory.resolve("out-" + heapMiB + "-" + mode);
        Path err = directory.resolve("err-" + heapMiB + "-" + mode);
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx" + heapMiB + "m", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=4",
                "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"),
                Probe.class.getName(), source.toString(), mode.name())
                .redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Child import did not finish within 60 seconds");
            return new Result(process.exitValue(), Files.readString(out), Files.readString(err));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private record Result(int exit, String output, String error) {}

    /** A valid, compressible 16-Mpixel fixture produced by our own lossless encoder. */
    private static byte[] sensorFile() throws Exception {
        int width = 4096, height = 4096, tileWidth = 2048;
        var sensor = Raster.createInterleavedRaster(DataBuffer.TYPE_USHORT, width, height, 1, null);
        short[] pixels = ((DataBufferUShort) sensor.getDataBuffer()).getData();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = (short) (4096 + (x * 3 + y * 5) % 8192);
            }
        }
        var directory = ByteBuffer.allocate(2 * 108);
        for (int tile = 0; tile < 2; tile++) {
            directory.putShort((short) 0xff01).putShort((short) 8).putInt(32).putInt(tile << 16);
            for (int plane = 0; plane < 4; plane++) {
                directory.putShort((short) 0xff02).putShort((short) 8).putInt(8).putInt(((plane << 4) | 8) << 24);
                directory.putShort((short) 0xff03).putShort((short) 8).putInt(8).putInt(0);
            }
        }
        var payload = new ByteArrayOutputStream();
        try (var output = new MemoryCacheImageOutputStream(payload)) {
            CrxLosslessEncoder.write(output, sensor, tileWidth, directory.array());
        }
        byte[] sample = payload.toByteArray();
        var cmp = ByteBuffer.allocate(33);
        cmp.putShort(4, (short) 1).putInt(8, width).putInt(12, height)
                .putInt(16, tileWidth).putInt(20, height).put(24, (byte) 14).put(25, (byte) 0x40)
                .put(27, (byte) 0x80).putInt(28, directory.capacity());
        var visual = ByteBuffer.allocate(82);
        visual.putShort(6, (short) 1).putShort(24, (short) width).putShort(26, (short) height);
        byte[] craw = buildBox("CRAW", concat(visual.array(), buildBox("CMP1", cmp.array())));
        byte[] stsd = buildBox("stsd", concat(ByteBuffer.allocate(8).putInt(0).putInt(1).array(), craw));
        byte[] stsz = buildBox("stsz", ByteBuffer.allocate(12).putInt(0).putInt(sample.length).putInt(1).array());
        byte[] ftyp = buildCr3FtypBox();
        int offset = ftyp.length + movie(stsd, stsz, 0).length + 8;
        return concat(ftyp, movie(stsd, stsz, offset), buildBox("mdat", sample));
    }

    private static byte[] movie(byte[] stsd, byte[] stsz, int offset) {
        byte[] stco = buildBox("stco", ByteBuffer.allocate(12).putInt(0).putInt(1).putInt(offset).array());
        return buildBox("moov", buildBox("trak", buildBox("mdia", buildBox("minf",
                buildBox("stbl", concat(stsd, stsz, stco))))));
    }

    public static final class Probe {
        public static void main(String[] args) throws Exception {
            try (var decoder = new Cr3Decoder()) {
                decoder.setInput(Path.of(args[0]));
                decoder.getMetadata().setOrientation(6);
                var image = decoder.decode(Cr3DecodeOptions.rawProcessed()
                        .bitDepth(Cr3DecodeOptions.BitDepth.SIXTEEN)
                        .memoryMode(Cr3DecodeOptions.MemoryMode.valueOf(args[1])));
                var hash = MessageDigest.getInstance("SHA-256");
                int[] row = new int[image.getWidth() * 3];
                byte[] bytes = new byte[row.length * 2];
                for (int y = 0; y < image.getHeight(); y++) {
                    image.getRaster().getPixels(0, y, image.getWidth(), 1, row);
                    for (int i = 0; i < row.length; i++) {
                        bytes[2 * i] = (byte) row[i];
                        bytes[2 * i + 1] = (byte) (row[i] >>> 8);
                    }
                    hash.update(bytes);
                }
                System.out.println("CR3_RESULT " + image.getWidth() + "x" + image.getHeight() + "/"
                        + image.getRaster().getTransferType() + "/" + HexFormat.of().formatHex(hash.digest()));
            }
        }
    }
}
