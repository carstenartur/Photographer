package com.photographer.focusstack.astro;

import com.photographer.focusstack.common.model.AstroStackingConfig;
import com.photographer.focusstack.common.model.AstroStackingMode;
import com.photographer.focusstack.common.model.FrameTransform;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * Stacks registered astronomical frames into a final 8-bit RGB image.
 *
 * <p>The historical {@link AstroStackingMode#MEAN} path retains integer-floor
 * arithmetic. Robust modes operate on the same registered 8-bit channel samples
 * without an additional color-space conversion. Median and sigma-clipped modes
 * process one output row at a time and use temporary storage proportional to the
 * number of input frames, rather than allocating a width × height × frames cube.</p>
 */
public class AstroStackingService {

    /** Input frame with its transform to the reference coordinate system. */
    public record FrameInput(BufferedImage image, FrameTransform transform) {
        public FrameInput {
            Objects.requireNonNull(image, "image");
            Objects.requireNonNull(transform, "transform");
        }
    }

    /** Cooperative cancellation and progress contract for long robust stacks. */
    public interface StackingControl {
        boolean isCancelled();

        default void onProgress(int completedRows, int totalRows) {
            // Optional observer callback.
        }

        static StackingControl none() {
            return NoOpStackingControl.INSTANCE;
        }
    }

    /**
     * Stacks using the historical mean algorithm.
     *
     * @param frames list of frames with their transforms
     * @param width output image width
     * @param height output image height
     * @return the stacked result image
     */
    public BufferedImage stack(List<FrameInput> frames, int width, int height) {
        return stack(
                frames,
                width,
                height,
                AstroStackingConfig.defaults(),
                StackingControl.none());
    }

    /** Stacks using the supplied explicit algorithm configuration. */
    public BufferedImage stack(
            List<FrameInput> frames,
            int width,
            int height,
            AstroStackingConfig config) {
        return stack(frames, width, height, config, StackingControl.none());
    }

    /**
     * Stacks using the supplied algorithm and cooperative control.
     *
     * <p>Sigma clipping uses population standard deviation and rejects one RGB
     * sample when any channel lies strictly beyond {@code threshold × sigma}.
     * Rejections in one iteration are decided from the same pre-rejection
     * statistics. A rejection set that would leave fewer than
     * {@code minimumSamples} is not applied. When the initial sample count is
     * smaller than that minimum, the pixel falls back to the historical mean.</p>
     */
    public BufferedImage stack(
            List<FrameInput> frames,
            int width,
            int height,
            AstroStackingConfig config,
            StackingControl control) {
        Objects.requireNonNull(frames, "frames");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(control, "control");
        if (frames.isEmpty()) {
            throw new IllegalArgumentException("No frames to stack");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Output dimensions must be positive");
        }
        int size;
        try {
            size = Math.multiplyExact(width, height);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Output dimensions are too large", overflow);
        }

        checkCancelled(control);
        return config.mode() == AstroStackingMode.MEAN
                ? mean(frames, width, height, size, control)
                : robust(frames, width, height, config, control);
    }

    private static BufferedImage mean(
            List<FrameInput> frames,
            int width,
            int height,
            int size,
            StackingControl control) {
        long[] sumR = new long[size];
        long[] sumG = new long[size];
        long[] sumB = new long[size];
        int[] count = new int[size];

        for (FrameInput frame : frames) {
            checkCancelled(control);
            BufferedImage image = frame.image();
            int imageWidth = image.getWidth();
            int[] pixels = Pixels.intBufferOf(image);
            double translationX = frame.transform().translationX();
            double translationY = frame.transform().translationY();

            for (int sourceY = 0; sourceY < image.getHeight(); sourceY++) {
                int referenceY = (int) Math.round(sourceY + translationY);
                if (referenceY < 0 || referenceY >= height) {
                    continue;
                }
                int sourceOffset = sourceY * imageWidth;
                for (int sourceX = 0; sourceX < imageWidth; sourceX++) {
                    int referenceX = (int) Math.round(sourceX + translationX);
                    if (referenceX < 0 || referenceX >= width) {
                        continue;
                    }
                    int rgb = pixels[sourceOffset + sourceX];
                    int index = referenceY * width + referenceX;
                    sumR[index] += (rgb >>> 16) & 0xFF;
                    sumG[index] += (rgb >>> 8) & 0xFF;
                    sumB[index] += rgb & 0xFF;
                    count[index]++;
                }
            }
        }

        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] resultPixels = Pixels.intBufferOf(result);
        for (int y = 0; y < height; y++) {
            checkCancelled(control);
            int rowOffset = y * width;
            for (int x = 0; x < width; x++) {
                int index = rowOffset + x;
                if (count[index] == 0) {
                    continue;
                }
                int red = (int) (sumR[index] / count[index]);
                int green = (int) (sumG[index] / count[index]);
                int blue = (int) (sumB[index] / count[index]);
                resultPixels[index] = pack(red, green, blue);
            }
            control.onProgress(y + 1, height);
        }
        return result;
    }

    private static BufferedImage robust(
            List<FrameInput> frames,
            int width,
            int height,
            AstroStackingConfig config,
            StackingControl control) {
        List<PreparedFrame> prepared = frames.stream()
                .map(PreparedFrame::from)
                .toList();
        int frameCount = prepared.size();
        int[] red = new int[frameCount];
        int[] green = new int[frameCount];
        int[] blue = new int[frameCount];
        boolean[] active = new boolean[frameCount];
        boolean[] rejected = new boolean[frameCount];

        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] resultPixels = Pixels.intBufferOf(result);
        int[] sourceRows = new int[frameCount];

        for (int referenceY = 0; referenceY < height; referenceY++) {
            checkCancelled(control);
            for (int frameIndex = 0; frameIndex < frameCount; frameIndex++) {
                PreparedFrame frame = prepared.get(frameIndex);
                sourceRows[frameIndex] = sourceCoordinate(
                        referenceY,
                        frame.translationY(),
                        frame.height());
            }

            int rowOffset = referenceY * width;
            for (int referenceX = 0; referenceX < width; referenceX++) {
                if ((referenceX & 0xFF) == 0) {
                    checkCancelled(control);
                }
                int samples = collect(
                        prepared,
                        sourceRows,
                        referenceX,
                        red,
                        green,
                        blue);
                if (samples == 0) {
                    continue;
                }
                int rgb = switch (config.mode()) {
                    case MEDIAN -> median(red, green, blue, samples);
                    case SIGMA_CLIPPED_MEAN -> sigmaClippedMean(
                            red,
                            green,
                            blue,
                            active,
                            rejected,
                            samples,
                            config);
                    case MEAN -> throw new IllegalStateException("Mean uses the dedicated path");
                };
                resultPixels[rowOffset + referenceX] = rgb;
            }
            control.onProgress(referenceY + 1, height);
        }
        return result;
    }

    private static int collect(
            List<PreparedFrame> frames,
            int[] sourceRows,
            int referenceX,
            int[] red,
            int[] green,
            int[] blue) {
        int count = 0;
        for (int frameIndex = 0; frameIndex < frames.size(); frameIndex++) {
            PreparedFrame frame = frames.get(frameIndex);
            int sourceY = sourceRows[frameIndex];
            if (sourceY < 0) {
                continue;
            }
            int sourceX = sourceCoordinate(
                    referenceX,
                    frame.translationX(),
                    frame.width());
            if (sourceX < 0) {
                continue;
            }
            int rgb = frame.pixels()[sourceY * frame.width() + sourceX];
            red[count] = (rgb >>> 16) & 0xFF;
            green[count] = (rgb >>> 8) & 0xFF;
            blue[count] = rgb & 0xFF;
            count++;
        }
        return count;
    }

    /** Inverts {@code reference = round(source + translation)} exactly. */
    private static int sourceCoordinate(
            int reference,
            double translation,
            int sourceLimit) {
        double candidate = Math.ceil(reference - translation - 0.5d);
        if (!Double.isFinite(candidate)
                || candidate < 0.0d
                || candidate >= sourceLimit) {
            return -1;
        }
        int source = (int) candidate;
        return Math.round(source + translation) == reference ? source : -1;
    }

    private static int median(
            int[] red,
            int[] green,
            int[] blue,
            int count) {
        Arrays.sort(red, 0, count);
        Arrays.sort(green, 0, count);
        Arrays.sort(blue, 0, count);
        return pack(
                medianChannel(red, count),
                medianChannel(green, count),
                medianChannel(blue, count));
    }

    private static int medianChannel(int[] values, int count) {
        int middle = count / 2;
        return (count & 1) == 1
                ? values[middle]
                : (values[middle - 1] + values[middle]) / 2;
    }

    private static int sigmaClippedMean(
            int[] red,
            int[] green,
            int[] blue,
            boolean[] active,
            boolean[] rejected,
            int count,
            AstroStackingConfig config) {
        if (count < config.minimumSamples()) {
            return arithmeticMean(red, green, blue, null, count);
        }
        Arrays.fill(active, 0, count, true);
        int activeCount = count;

        for (int iteration = 0; iteration < config.maxIterations(); iteration++) {
            ChannelStatistics redStats = statistics(red, active, count, activeCount);
            ChannelStatistics greenStats = statistics(green, active, count, activeCount);
            ChannelStatistics blueStats = statistics(blue, active, count, activeCount);
            Arrays.fill(rejected, 0, count, false);

            int survivors = activeCount;
            for (int index = 0; index < count; index++) {
                if (!active[index]) {
                    continue;
                }
                boolean outlier = outside(red[index], redStats, config.sigmaThreshold())
                        || outside(green[index], greenStats, config.sigmaThreshold())
                        || outside(blue[index], blueStats, config.sigmaThreshold());
                if (outlier) {
                    rejected[index] = true;
                    survivors--;
                }
            }

            if (survivors == activeCount || survivors < config.minimumSamples()) {
                break;
            }
            for (int index = 0; index < count; index++) {
                if (rejected[index]) {
                    active[index] = false;
                }
            }
            activeCount = survivors;
        }
        return arithmeticMean(red, green, blue, active, count);
    }

    private static ChannelStatistics statistics(
            int[] values,
            boolean[] active,
            int count,
            int activeCount) {
        double sum = 0.0d;
        for (int index = 0; index < count; index++) {
            if (active[index]) {
                sum += values[index];
            }
        }
        double mean = sum / activeCount;
        double squared = 0.0d;
        for (int index = 0; index < count; index++) {
            if (active[index]) {
                double difference = values[index] - mean;
                squared += difference * difference;
            }
        }
        return new ChannelStatistics(mean, Math.sqrt(squared / activeCount));
    }

    private static boolean outside(
            int value,
            ChannelStatistics statistics,
            double threshold) {
        return statistics.standardDeviation() > 0.0d
                && Math.abs(value - statistics.mean())
                > threshold * statistics.standardDeviation();
    }

    private static int arithmeticMean(
            int[] red,
            int[] green,
            int[] blue,
            boolean[] active,
            int count) {
        long redSum = 0L;
        long greenSum = 0L;
        long blueSum = 0L;
        int included = 0;
        for (int index = 0; index < count; index++) {
            if (active == null || active[index]) {
                redSum += red[index];
                greenSum += green[index];
                blueSum += blue[index];
                included++;
            }
        }
        return pack(
                (int) (redSum / included),
                (int) (greenSum / included),
                (int) (blueSum / included));
    }

    private static int pack(int red, int green, int blue) {
        return (red << 16) | (green << 8) | blue;
    }

    private static void checkCancelled(StackingControl control) {
        if (control.isCancelled()) {
            throw new CancellationException("Astro stacking cancelled");
        }
    }

    private record PreparedFrame(
            int[] pixels,
            int width,
            int height,
            double translationX,
            double translationY) {
        private static PreparedFrame from(FrameInput input) {
            BufferedImage image = input.image();
            return new PreparedFrame(
                    Pixels.intBufferOf(image),
                    image.getWidth(),
                    image.getHeight(),
                    input.transform().translationX(),
                    input.transform().translationY());
        }
    }

    private record ChannelStatistics(double mean, double standardDeviation) {}

    private enum NoOpStackingControl implements StackingControl {
        INSTANCE;

        @Override
        public boolean isCancelled() {
            return false;
        }
    }
}
