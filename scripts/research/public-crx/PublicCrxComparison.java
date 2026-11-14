import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.imageio.ImageIO;
import javax.imageio.plugins.tiff.TIFFDirectory;

/** Compare the full Bayer plane from the released LightCraft and DNGLab executables. */
public final class PublicCrxComparison {
    // raw.pixls.us input identities and LightCraft's published full-sensor FNV-1a (u16le).
    record Sample(String name, int id, int width, int height, String inputSha256, String sensorFnv) {}
    static final Sample[] SAMPLES = {
        new Sample("eos-r", 4543, 6888, 4546, "1b3efe0664a28a0decb53e225cbfecdbc0dd27d85abdc4683fc2a4add5129b4d", "f9cd81a3249fcfc7"),
        new Sample("g5xm2", 3506, 5632, 3710, "16d7b944344004c866bf2f84c5673969e76adfe16e5fb4388da48c72bdd58058", "fed8f701f231442f")
    };

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length == 0 ? "." : args[0]);
        boolean matches = true;
        System.out.println("name\tid\twidth\theight\tsamples\tmismatched\tfirstMismatch(x,y,dnglab,lightcraft)\tmaxAbsDifference\tdnglabSha256LE\tlightcraftSha256LE\tdnglabFnvLE\tlightcraftFnvLE\tpublishedFnvLE");
        for (Sample s : SAMPLES) matches &= compare(dir, s);
        if (!matches) throw new IllegalStateException("Sensor comparison failed");
    }

    static String fileSha(Path file) throws Exception {
        var sha = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buf = new byte[65536];
            for (int n; (n = input.read(buf)) != -1;) sha.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    static long fnv(long hash, byte[] data) {
        for (byte b : data) hash = (hash ^ (b & 255)) * 0x100000001b3L;
        return hash;
    }

    static boolean compare(Path dir, Sample sample) throws Exception {
        if (!sample.inputSha256.equals(fileSha(dir.resolve(sample.name + ".cr3"))))
            throw new IllegalArgumentException("Input SHA-256 mismatch: " + sample.name);
        var dnglabSha = MessageDigest.getInstance("SHA-256");
        var lightSha = MessageDigest.getInstance("SHA-256");
        long dnglabFnv = 0xcbf29ce484222325L, lightFnv = dnglabFnv;
        int mismatches = 0, maxDifference = 0;
        String firstMismatch = "-";
        var reader = ImageIO.getImageReadersByFormatName("TIFF").next();
        try (var dng = ImageIO.createImageInputStream(dir.resolve(sample.name + "-lightcraft.dng").toFile());
             var pgm = new DataInputStream(Files.newInputStream(dir.resolve(sample.name + ".sample")))) {
            // DNGLab 0.8.0 emits this one-line Netpbm header and big-endian binary samples.
            String expectedHeader = "P5 " + sample.width + " " + sample.height + " 65535\n";
            byte[] header = expectedHeader.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            if (!java.util.Arrays.equals(pgm.readNBytes(header.length), header))
                throw new IllegalArgumentException("Unexpected sensor PGM header");
            reader.setInput(dng);
            var tags = TIFFDirectory.createFromMetadata(reader.getImageMetadata(0));
            require(tags, 256, sample.width);
            require(tags, 257, sample.height);
            require(tags, 258, 16);
            require(tags, 259, 1);      // uncompressed
            require(tags, 262, 32803);  // CFA
            require(tags, 277, 1);      // one sample per site
            int stripRows = Math.toIntExact(tags.getTIFFField(278).getAsLong(0));
            var offsets = tags.getTIFFField(273);
            var lengths = tags.getTIFFField(279);
            int expectedStrips = Math.ceilDiv(sample.height, stripRows);
            if (offsets.getCount() != expectedStrips || lengths.getCount() != expectedStrips)
                throw new IllegalArgumentException("Unexpected strip count");
            dng.seek(0);
            if (dng.readUnsignedByte() != 'I' || dng.readUnsignedByte() != 'I')
                throw new IllegalArgumentException("Expected Little Endian DNG from LightCraft");
            int rowBytes = 2 * sample.width;
            byte[] dc = new byte[rowBytes], lc = new byte[rowBytes];
            for (int y = 0; y < sample.height; y++) {
                int strip = y / stripRows;
                if (y % stripRows == 0) {
                    long expectedBytes = (long) Math.min(stripRows, sample.height - y) * rowBytes;
                    if (lengths.getAsLong(strip) != expectedBytes) throw new IllegalArgumentException("Invalid strip size");
                    dng.seek(offsets.getAsLong(strip));
                }
                dng.readFully(lc);
                pgm.readFully(dc);
                for (int x = 0; x < sample.width; x++) {
                    int pos = x * 2;
                    int a = ((dc[pos] & 255) << 8) | dc[pos + 1] & 255;
                    int b = ((lc[pos + 1] & 255) << 8) | lc[pos] & 255;
                    if (a != b) {
                        if (mismatches == 0) firstMismatch = x + "," + y + "," + a + "," + b;
                        mismatches++;
                        maxDifference = Math.max(maxDifference, Math.abs(a - b));
                    }
                    byte hi = dc[pos];
                    dc[pos] = dc[pos + 1];
                    dc[pos + 1] = hi;
                }
                dnglabSha.update(dc);
                lightSha.update(lc);
                dnglabFnv = fnv(dnglabFnv, dc);
                lightFnv = fnv(lightFnv, lc);
            }
            if (pgm.read() != -1) throw new IllegalArgumentException("Trailing PGM data");
        } finally {
            reader.dispose();
        }
        var hex = HexFormat.of();
        String dnglabFnvHex = hex.toHexDigits(dnglabFnv), lightFnvHex = hex.toHexDigits(lightFnv);
        System.out.printf("%s\t%d\t%d\t%d\t%d\t%d\t%s\t%d\t%s\t%s\t%s\t%s\t%s%n", sample.name, sample.id, sample.width, sample.height,
            sample.width * sample.height, mismatches, firstMismatch, maxDifference, hex.formatHex(dnglabSha.digest()),
            hex.formatHex(lightSha.digest()), dnglabFnvHex, lightFnvHex, sample.sensorFnv);
        return mismatches == 0 && sample.sensorFnv.equals(lightFnvHex);
    }

    static void require(TIFFDirectory tags, int tag, long value) {
        var f = tags.getTIFFField(tag);
        if (f == null || f.getCount() != 1 || f.getAsLong(0) != value)
            throw new IllegalArgumentException("Unexpected DNG tag " + tag + " (expected " + value + ")");
    }
}
