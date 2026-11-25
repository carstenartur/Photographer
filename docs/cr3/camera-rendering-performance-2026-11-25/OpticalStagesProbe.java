import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.decode.Cr3Decoder;
import com.photographer.focusstack.cr3.processing.CameraToneCurve;
import com.photographer.focusstack.cr3.processing.LensProfiles;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferUShort;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

public class OpticalStagesProbe {
    public static void main(String[] args) throws Exception {
        var file = Path.of(args[0]);
        int warmup = Integer.parseInt(args[1]), iterations = Integer.parseInt(args[2]);
        int workers = Integer.parseInt(args[3]);
        try (var decoder = new Cr3Decoder()) {
            decoder.setInput(file);
            var meta = decoder.getMetadata();
            var lens = LensProfiles.find(meta.cameraModel(), meta.lensModel(), meta.focalLengthMm(),
                    meta.fNumber(), meta.focusDistanceMetres(), meta.visibleWidth(), meta.visibleHeight()).orElseThrow();
            var base = decoder.decode(Cr3DecodeOptions.rawProcessed(Cr3DecodeOptions.BitDepth.SIXTEEN)
                    .applyLensCorrection(false).applyCameraTone(false).maxWorkers(workers));
            var image = new BufferedImage(base.getColorModel(), base.getRaster().createCompatibleWritableRaster(), false, null);
            var original = ((DataBufferUShort) base.getRaster().getDataBuffer()).getBankData();
            var destination = ((DataBufferUShort) image.getRaster().getDataBuffer()).getBankData();
            for (int n = -warmup; n < iterations; n++) {
                for (int band = 0; band < 3; band++) System.arraycopy(original[band], 0, destination[band], 0, original[band].length);
                long start = System.nanoTime();
                lens.applyToOwned(image, true, workers);
                long optical = System.nanoTime() - start;
                String opticalHash = n == iterations - 1 ? hash(image) : "";
                start = System.nanoTime();
                CameraToneCurve.apply(image, meta.cameraModel(), meta.pictureStyle(), workers);
                long tone = System.nanoTime() - start;
                String toneHash = n == iterations - 1 ? hash(image) : "";
                if (n >= 0) System.out.printf(Locale.ROOT,
                        "{\"input\":\"%s\",\"iteration\":%d,\"warmup\":%d,\"workers\":%d,\"optical_ms\":%.6f,\"tone_ms\":%.6f,\"optical_sha256\":\"%s\",\"tone_sha256\":\"%s\"}%n",
                        file.getFileName(), n, warmup, workers, optical / 1e6, tone / 1e6, opticalHash, toneHash);
            }
        }
    }

    static String hash(BufferedImage image) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        int[] samples = new int[image.getWidth() * 3];
        byte[] row = new byte[samples.length * 2];
        for (int y = 0; y < image.getHeight(); y++) {
            image.getRaster().getPixels(0, y, image.getWidth(), 1, samples);
            for (int i = 0; i < samples.length; i++) {
                row[2 * i] = (byte) samples[i];
                row[2 * i + 1] = (byte) (samples[i] >>> 8);
            }
            digest.update(row);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
