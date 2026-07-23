package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.camera.exception.CameraOperationException;
import com.photographer.focusstack.common.camera.LiveViewFrame;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.CAMERA_REJECTED;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonCcapiScrollLiveViewReaderTest {

    private static final String PROFILE = "scroll-camera";
    private static final String URL =
            "http://camera.example/ccapi/ver110/shooting/liveview/scroll";

    @Test
    void parsesJpegAcrossArbitraryReadsAndCancelsRemoteOperationOnce() {
        byte[] expected = jpeg(3, 2, 0x21);
        BlockingFragmentedInputStream input = new BlockingFragmentedInputStream(expected, 1);
        RecordingTransport transport = new RecordingTransport(input);
        CanonCcapiScrollLiveViewReader reader = reader(transport, Duration.ofSeconds(1));

        LiveViewFrame frame = reader.nextFrame(URL);

        assertThat(frame.data()).containsExactly(expected);
        assertThat(frame.width()).isEqualTo(3);
        assertThat(frame.height()).isEqualTo(2);
        assertThat(frame.contentType()).isEqualTo("image/jpeg");
        assertThat(reader.active()).isTrue();

        reader.stop();
        reader.stop();

        assertThat(transport.reads).hasValue(1);
        assertThat(transport.cancels).hasValue(1);
        assertThat(reader.active()).isFalse();
    }

    @Test
    void timesOutWithBoundedDeleteAndNoOrphanReader() throws Exception {
        BlockingFragmentedInputStream input = new BlockingFragmentedInputStream(new byte[0], 1);
        RecordingTransport transport = new RecordingTransport(input);
        CanonCcapiScrollLiveViewReader reader = reader(transport, Duration.ofMillis(30));

        assertThatThrownBy(() -> reader.nextFrame(URL))
                .isInstanceOfSatisfying(CameraOperationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(TIMEOUT));

        assertThat(transport.started.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(transport.cancels).hasValue(1);
        assertThat(reader.active()).isFalse();
    }

    @Test
    void rejectsTruncatedJpegWithoutReturningPartialBytes() {
        BlockingFragmentedInputStream input = BlockingFragmentedInputStream.finite(
                new byte[]{(byte) 0xff, (byte) 0xd8, 1, 2, 3}, 2);
        RecordingTransport transport = new RecordingTransport(input);
        CanonCcapiScrollLiveViewReader reader = reader(transport, Duration.ofSeconds(1));

        assertThatThrownBy(() -> reader.nextFrame(URL))
                .isInstanceOfSatisfying(CameraOperationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(CAMERA_REJECTED));

        assertThat(transport.cancels).hasValue(0);
        assertThat(reader.active()).isFalse();
    }

    private static CanonCcapiScrollLiveViewReader reader(
            RecordingTransport transport,
            Duration frameTimeout) {
        return new CanonCcapiScrollLiveViewReader(
                PROFILE,
                transport,
                frameTimeout,
                Duration.ofSeconds(1));
    }

    private static byte[] jpeg(int width, int height, int marker) {
        return new byte[]{
                (byte) 0xff, (byte) 0xd8,
                (byte) 0xff, (byte) 0xc0,
                0x00, 0x11,
                0x08,
                (byte) (height >>> 8), (byte) height,
                (byte) (width >>> 8), (byte) width,
                0x03,
                0x01, 0x11, 0x00,
                0x02, 0x11, 0x00,
                0x03, 0x11, (byte) marker,
                (byte) 0xff, (byte) 0xd9
        };
    }

    private static final class RecordingTransport
            implements CanonCcapiScrollLiveViewReader.Transport {
        private final InputStream input;
        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicInteger cancels = new AtomicInteger();
        private final CountDownLatch started = new CountDownLatch(1);

        private RecordingTransport(InputStream input) {
            this.input = input;
        }

        @Override
        public void read(
                String url,
                CanonCcapiScrollLiveViewReader.StreamHandler handler) throws Exception {
            assertThat(url).isEqualTo(URL);
            reads.incrementAndGet();
            started.countDown();
            handler.read(input);
        }

        @Override
        public void cancel(String url) {
            assertThat(url).isEqualTo(URL);
            cancels.incrementAndGet();
        }
    }

    private static final class BlockingFragmentedInputStream extends InputStream {
        private final byte[] data;
        private final int fragmentSize;
        private final boolean blockAtEnd;
        private int offset;
        private boolean closed;

        private BlockingFragmentedInputStream(byte[] data, int fragmentSize) {
            this(data, fragmentSize, true);
        }

        private BlockingFragmentedInputStream(
                byte[] data,
                int fragmentSize,
                boolean blockAtEnd) {
            this.data = data.clone();
            this.fragmentSize = fragmentSize;
            this.blockAtEnd = blockAtEnd;
        }

        private static BlockingFragmentedInputStream finite(
                byte[] data,
                int fragmentSize) {
            return new BlockingFragmentedInputStream(data, fragmentSize, false);
        }

        @Override
        public synchronized int read() throws IOException {
            byte[] value = new byte[1];
            int count = read(value, 0, 1);
            return count < 0 ? -1 : Byte.toUnsignedInt(value[0]);
        }

        @Override
        public synchronized int read(byte[] target, int targetOffset, int length)
                throws IOException {
            while (offset >= data.length && blockAtEnd && !closed) {
                try {
                    wait();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("reader interrupted", interrupted);
                }
            }
            if (closed || offset >= data.length) {
                return -1;
            }
            int count = Math.min(Math.min(fragmentSize, length), data.length - offset);
            System.arraycopy(data, offset, target, targetOffset, count);
            offset += count;
            return count;
        }

        @Override
        public synchronized void close() {
            closed = true;
            notifyAll();
        }
    }
}
