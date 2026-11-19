import com.photographer.focusstack.cr3.imagej.Cr3ColorImporter;
import com.photographer.focusstack.cr3.imagej.Cr3SensorImporter;
import ij.CompositeImage;
import ij.ImagePlus;
import ij.process.ShortProcessor;

import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;

/** One fresh headless import through the public ImageJ adapter, followed by output verification. */
public final class Cr3ImageJProbe {
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3
                || !(args[0].equals("sensor") || args[0].equals("color"))
                || (args.length == 3 && !args[2].equals("--checksum"))) {
            throw new IllegalArgumentException("Usage: Cr3ImageJProbe sensor|color PATH [--checksum]");
        }
        require(GraphicsEnvironment.isHeadless(), "Launch with -Djava.awt.headless=true");
        boolean color = args[0].equals("color");
        boolean full = args.length == 3;
        Path input = Path.of(args[1]).toAbsolutePath().normalize();
        ImagePlus image = color ? Cr3ColorImporter.open(input) : Cr3SensorImporter.open(input);
        try {
            int channels = color ? 3 : 1;
            int width = image.getWidth();
            int height = image.getHeight();
            require(image.getWindow() == null, "The importer opened a window");
            require(width > 0 && height > 0, "Invalid image dimensions");
            require(Arrays.equals(image.getDimensions(), new int[]{width, height, channels, 1, 1})
                    && image.getStackSize() == channels, "Unexpected channel, slice or frame dimensions");
            require(image.getBitDepth() == 16 && image.getType() == ImagePlus.GRAY16,
                    "Expected unsigned 16-bit ImageJ planes");
            if (color) {
                require(image instanceof CompositeImage, "Expected a composite color image");
                CompositeImage composite = (CompositeImage) image;
                require(composite.getMode() == CompositeImage.COMPOSITE, "Expected composite display mode");
                for (int c = 1; c <= channels; c++) {
                    var lut = composite.getChannelLut(c);
                    require(lut.min == 0 && lut.max == 65535, "Unexpected display range for channel " + c);
                }
            }
            short[][] planes = new short[channels][];
            for (int c = 0; c < channels; c++) {
                var processor = image.getStack().getProcessor(c + 1);
                require(processor instanceof ShortProcessor && processor.getWidth() == width
                        && processor.getHeight() == height, "Unexpected processor for channel " + (c + 1));
                planes[c] = (short[]) processor.getPixels();
                require(planes[c].length == Math.multiplyExact(width, height), "Unexpected plane length");
            }
            int rowStep = full ? 1 : Math.max(1, height / 64);
            long hash = fingerprint(planes, width, height, rowStep);
            Runtime runtime = Runtime.getRuntime();
            System.out.printf(Locale.ROOT, "{\"input\":%s,\"imagej_mode\":%s,\"mode\":%s,"
                            + "\"width\":%d,\"height\":%d,\"channels\":%d,\"bits\":16,"
                            + "\"checksum_kind\":%s,\"fnv1a64\":\"%016x\","
                            + "\"samples\":[%s,%s,%s],\"headless\":true,\"window_open\":false,"
                            + "\"composite\":%b,\"requested_memory_mode\":\"AUTO\","
                            + "\"java_version\":%s,\"java_vm\":%s,\"available_processors\":%d,"
                            + "\"max_heap_bytes\":%d}%n",
                    quote(input.toString()), quote(args[0]), quote(color ? "rgb16" : "sensor"),
                    width, height, channels, quote(full ? "full-u16le-or-u8" : "sampled-rows-u16le-or-u8"),
                    hash, sample(planes, width, 0, 0), sample(planes, width, width / 2, height / 2),
                    sample(planes, width, width - 1, height - 1), color, quote(Runtime.version().toString()),
                    quote(System.getProperty("java.vm.name")), runtime.availableProcessors(), runtime.maxMemory());
        } finally {
            image.close();
            image.flush();
        }
    }

    private static long fingerprint(short[][] planes, int width, int height, int rowStep) {
        long hash = 0xcbf29ce484222325L;
        for (int y = 0; y < height; y += rowStep) {
            int end = (y + 1) * width;
            for (int i = y * width; i < end; i++) {
                for (short[] plane : planes) {
                    int sample = Short.toUnsignedInt(plane[i]);
                    hash = (hash ^ (sample & 255)) * 0x100000001b3L;
                    hash = (hash ^ (sample >>> 8)) * 0x100000001b3L;
                }
            }
        }
        return hash;
    }

    private static String sample(short[][] planes, int width, int x, int y) {
        int[] values = new int[planes.length];
        for (int c = 0; c < planes.length; c++) {
            values[c] = Short.toUnsignedInt(planes[c][y * width + x]);
        }
        return "{\"x\":" + x + ",\"y\":" + y + ",\"values\":" + Arrays.toString(values) + "}";
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < ' ') {
                out.append("\\u").append(HexFormat.of().toHexDigits(c));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
