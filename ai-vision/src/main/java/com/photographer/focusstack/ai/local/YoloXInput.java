package com.photographer.focusstack.ai.local;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/** BGR letterbox input used by the versioned ONNX export. */
final class YoloXInput {

    static final int SIZE = 416;
    static final int MAX_ENCODED_BYTES = 16 * 1024 * 1024;

    private static final int MAX_AXIS = 8192;
    private static final long MAX_PIXELS = 16L * 1024 * 1024;
    private static final float PAD = 114;

    private YoloXInput() {}

    static FloatBuffer allocateBuffer() {
        return ByteBuffer.allocateDirect(3 * SIZE * SIZE * Float.BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
    }

    static ImageGeometry prepare(BufferedImage source, FloatBuffer target) {
        double scale = Math.min((double) SIZE / source.getWidth(), (double) SIZE / source.getHeight());
        int width = Math.max(1, (int) (source.getWidth() * scale));
        int height = Math.max(1, (int) (source.getHeight() * scale));
        var scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        int[] pixels = scaled.getRGB(0, 0, width, height, null, 0, width);
        target.clear();
        for (int shift = 0; shift < 24; shift += 8) {
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    target.put(y < height && x < width ? (pixels[y * width + x] >>> shift) & 0xff : PAD);
                }
            }
        }
        target.flip();
        return new ImageGeometry(source.getWidth(), source.getHeight(), scale);
    }

    static BufferedImage readImage(byte[] encoded) throws IOException {
        if (encoded == null || encoded.length == 0) {
            throw new IOException("No image bytes were supplied");
        }
        if (encoded.length > MAX_ENCODED_BYTES) {
            throw new IOException("The encoded image exceeds 16 MiB");
        }
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(encoded))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("A readable JPEG or PNG image is required");
            }
            var reader = readers.next();
            try {
                String format = reader.getFormatName();
                if (!"JPEG".equalsIgnoreCase(format) && !"PNG".equalsIgnoreCase(format)) {
                    throw new IOException("A readable JPEG or PNG image is required");
                }
                reader.setInput(input, true, true);
                int width;
                int height;
                try {
                    width = reader.getWidth(0);
                    height = reader.getHeight(0);
                } catch (IOException | RuntimeException e) {
                    throw new IOException("The image dimensions could not be read", e);
                }
                if (width < 1 || height < 1 || width > MAX_AXIS || height > MAX_AXIS
                        || (long) width * height > MAX_PIXELS) {
                    throw new IOException("The image dimensions exceed the decoding limits");
                }
                try {
                    // Some readers return partial pixels and report truncated data only as a warning.
                    var warned = new AtomicBoolean();
                    reader.addIIOReadWarningListener((source, warning) -> warned.set(true));
                    BufferedImage image = reader.read(0);
                    if (warned.get()) {
                        throw new IOException("The image could not be decoded completely");
                    }
                    return image;
                } catch (IOException | RuntimeException e) {
                    throw new IOException("The image contents could not be decoded", e);
                }
            } finally {
                reader.dispose();
            }
        }
    }

    record ImageGeometry(int width, int height, double scale) {}
}
