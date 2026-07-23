package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.camera.exception.CameraOperationException;
import com.photographer.focusstack.common.camera.LiveViewFrame;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.CAMERA_REJECTED;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.CANCELLED;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.NOT_STARTED;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.SERVICE_UNAVAILABLE;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.TIMEOUT;

/**
 * Owns one Canon CCAPI scroll live-view GET operation and its matching DELETE.
 *
 * <p>Spring/Apache de-chunk HTTP transfer encoding before exposing the response
 * stream, so network read boundaries are not treated as Canon frame boundaries.
 * JPEG SOI/EOI markers delimit frames instead. Only the latest complete frame is
 * retained, which bounds memory when the camera produces frames faster than a
 * caller consumes them.</p>
 */
final class CanonCcapiScrollLiveViewReader {

    private static final int JPEG_SOI_FIRST = 0xFF;
    private static final int JPEG_SOI_SECOND = 0xD8;
    private static final int JPEG_EOI_SECOND = 0xD9;
    private static final int MAX_JPEG_BYTES = 32 * 1024 * 1024;
    private static final Duration DEFAULT_FRAME_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_STOP_TIMEOUT = Duration.ofSeconds(3);

    @FunctionalInterface
    interface StreamHandler {
        void read(InputStream input) throws IOException;
    }

    interface Transport {
        void read(String url, StreamHandler handler) throws Exception;

        void cancel(String url);
    }

    private final String profileId;
    private final Transport transport;
    private final Duration frameTimeout;
    private final Duration stopTimeout;
    private final Object monitor = new Object();

    private Session active;

    CanonCcapiScrollLiveViewReader(String profileId, RestTemplate restTemplate) {
        this(
                profileId,
                new RestTemplateTransport(Objects.requireNonNull(restTemplate, "restTemplate")),
                DEFAULT_FRAME_TIMEOUT,
                DEFAULT_STOP_TIMEOUT);
    }

    CanonCcapiScrollLiveViewReader(
            String profileId,
            Transport transport,
            Duration frameTimeout,
            Duration stopTimeout) {
        this.profileId = requireProfileId(profileId);
        this.transport = Objects.requireNonNull(transport, "transport");
        this.frameTimeout = requirePositive(frameTimeout, "frameTimeout");
        this.stopTimeout = requirePositive(stopTimeout, "stopTimeout");
    }

    LiveViewFrame nextFrame(String url) {
        Session session = ensureSession(url);
        try {
            LiveViewFrame immediate = session.latest.poll();
            if (immediate != null) {
                return immediate;
            }
            RuntimeException terminal = session.failure;
            if (terminal != null) {
                discard(session);
                throw terminal;
            }

            if (!session.available.tryAcquire(
                    frameTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
                stopSession(session, true, false);
                throw failure(TIMEOUT, null);
            }

            LiveViewFrame frame = session.latest.poll();
            if (frame != null) {
                return frame;
            }
            terminal = session.failure;
            if (terminal != null) {
                discard(session);
                throw terminal;
            }
            stopSession(session, true, false);
            throw failure(SERVICE_UNAVAILABLE, null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            stopSession(session, true, false);
            throw failure(CANCELLED, interrupted);
        }
    }

    /** Stops the remote scroll operation exactly once and waits for the local reader. */
    void stop() {
        Session session;
        synchronized (monitor) {
            session = active;
        }
        if (session != null) {
            stopSession(session, true, true);
        }
    }

    /** Drops a failed local reader without issuing a redundant DELETE correction. */
    void abort() {
        Session session;
        synchronized (monitor) {
            session = active;
        }
        if (session != null) {
            stopSession(session, false, false);
        }
    }

    boolean active() {
        synchronized (monitor) {
            return active != null && !active.cancelled.get();
        }
    }

    private Session ensureSession(String url) {
        String value = requireUrl(url);
        synchronized (monitor) {
            if (active != null) {
                if (!active.url.equals(value)) {
                    throw new IllegalStateException(
                            "a different CCAPI scroll endpoint is already active");
                }
                if (active.cancelled.get()) {
                    throw failure(CANCELLED, null);
                }
                return active;
            }

            Session created = new Session(value);
            Thread worker = Thread.ofVirtual()
                    .name("ccapi-live-view-scroll-" + safeThreadToken(profileId))
                    .unstarted(() -> read(created));
            created.worker = worker;
            active = created;
            worker.start();
            return created;
        }
    }

    private void read(Session session) {
        try {
            transport.read(session.url, input -> {
                if (input == null) {
                    throw new IOException("CCAPI scroll response has no body");
                }
                session.input = input;
                readJpegs(session, input);
            });
            if (!session.cancelled.get()) {
                session.fail(failure(SERVICE_UNAVAILABLE, null));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (!session.cancelled.get()) {
                session.fail(failure(CANCELLED, interrupted));
            }
        } catch (RuntimeException runtime) {
            if (!session.cancelled.get()) {
                session.fail(runtime);
            }
        } catch (Exception exception) {
            if (!session.cancelled.get()) {
                session.fail(failure(CAMERA_REJECTED, exception));
            }
        } finally {
            closeQuietly(session.input);
            session.finished.countDown();
        }
    }

    private void readJpegs(Session session, InputStream input) throws IOException {
        ByteArrayOutputStream jpeg = null;
        int previous = -1;
        byte[] buffer = new byte[8192];

        while (!session.cancelled.get()) {
            int count = input.read(buffer);
            if (count < 0) {
                if (jpeg != null) {
                    throw new IOException("CCAPI scroll ended inside a JPEG frame");
                }
                return;
            }
            if (count == 0) {
                continue;
            }

            for (int index = 0; index < count; index++) {
                if (session.cancelled.get()) {
                    return;
                }
                int value = Byte.toUnsignedInt(buffer[index]);
                if (jpeg == null) {
                    if (previous == JPEG_SOI_FIRST && value == JPEG_SOI_SECOND) {
                        jpeg = new ByteArrayOutputStream(256 * 1024);
                        jpeg.write(JPEG_SOI_FIRST);
                        jpeg.write(JPEG_SOI_SECOND);
                    }
                    previous = value;
                    continue;
                }

                jpeg.write(value);
                if (jpeg.size() > MAX_JPEG_BYTES) {
                    throw new IOException("CCAPI scroll JPEG exceeds the bounded frame size");
                }
                if (previous == JPEG_SOI_FIRST && value == JPEG_EOI_SECOND) {
                    session.publish(frame(jpeg.toByteArray()));
                    jpeg = null;
                    previous = -1;
                } else {
                    previous = value;
                }
            }
        }
    }

    private LiveViewFrame frame(byte[] jpeg) {
        int[] dimensions = jpegDimensions(jpeg);
        return new LiveViewFrame(
                jpeg,
                dimensions[0],
                dimensions[1],
                Instant.now(),
                MediaType.IMAGE_JPEG_VALUE);
    }

    private void stopSession(Session session, boolean sendDelete, boolean propagateFailure) {
        if (!session.cancelled.compareAndSet(false, true)) {
            awaitFinished(session, propagateFailure);
            clear(session);
            return;
        }

        session.latest.clear();
        session.fail(failure(CANCELLED, null));
        RuntimeException cancelFailure = null;
        if (sendDelete && session.deleteSent.compareAndSet(false, true)
                && session.finished.getCount() != 0L) {
            try {
                transport.cancel(session.url);
            } catch (RuntimeException failure) {
                if (!hasReason(failure, NOT_STARTED)) {
                    cancelFailure = failure;
                }
            }
        }

        closeQuietly(session.input);
        Thread worker = session.worker;
        if (worker != null) {
            worker.interrupt();
        }
        boolean finished = awaitFinished(session, propagateFailure);
        clear(session);

        if (propagateFailure && cancelFailure != null) {
            throw cancelFailure;
        }
        if (propagateFailure && !finished) {
            throw failure(TIMEOUT, null);
        }
    }

    private boolean awaitFinished(Session session, boolean propagateFailure) {
        boolean restoreInterrupt = false;
        try {
            return session.finished.await(
                    stopTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            restoreInterrupt = true;
            if (propagateFailure) {
                throw failure(CANCELLED, interrupted);
            }
            return false;
        } finally {
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void discard(Session session) {
        stopSession(session, false, false);
    }

    private void clear(Session session) {
        synchronized (monitor) {
            if (active == session) {
                active = null;
            }
        }
    }

    private CameraOperationException failure(
            CameraOperationException.Reason reason,
            Throwable cause) {
        return new CameraOperationException(profileId, reason, null, cause);
    }

    private static boolean hasReason(
            Throwable failure,
            CameraOperationException.Reason expected) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof CameraOperationException operation
                    && operation.reason() == expected) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static int[] jpegDimensions(byte[] data) {
        int index = 2;
        while (index + 8 < data.length) {
            if (Byte.toUnsignedInt(data[index]) != 0xFF) {
                index++;
                continue;
            }
            while (index < data.length && Byte.toUnsignedInt(data[index]) == 0xFF) {
                index++;
            }
            if (index >= data.length) {
                break;
            }
            int marker = Byte.toUnsignedInt(data[index++]);
            if (marker == 0xD8 || marker == 0xD9 || marker == 0x01
                    || (marker >= 0xD0 && marker <= 0xD7)) {
                continue;
            }
            if (index + 1 >= data.length) {
                break;
            }
            int length = (Byte.toUnsignedInt(data[index]) << 8)
                    | Byte.toUnsignedInt(data[index + 1]);
            if (length < 2 || index + length > data.length) {
                break;
            }
            if (isStartOfFrame(marker) && length >= 7) {
                int height = (Byte.toUnsignedInt(data[index + 3]) << 8)
                        | Byte.toUnsignedInt(data[index + 4]);
                int width = (Byte.toUnsignedInt(data[index + 5]) << 8)
                        | Byte.toUnsignedInt(data[index + 6]);
                return new int[]{width, height};
            }
            index += length;
        }
        return new int[]{0, 0};
    }

    private static boolean isStartOfFrame(int marker) {
        return switch (marker) {
            case 0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7,
                    0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF -> true;
            default -> false;
        };
    }

    private static void closeQuietly(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (IOException ignored) {
            // Closing is best-effort after protocol cancellation.
        }
    }

    private static String requireProfileId(String value) {
        Objects.requireNonNull(value, "profileId");
        if (value.isBlank()) {
            throw new IllegalArgumentException("profileId must not be blank");
        }
        return value;
    }

    private static String requireUrl(String value) {
        Objects.requireNonNull(value, "url");
        URI uri = URI.create(value);
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("scroll URL must be an absolute camera URL");
        }
        return uri.toString();
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static String safeThreadToken(String value) {
        String sanitized = value.replaceAll("[^a-zA-Z0-9._-]", "-");
        return sanitized.length() <= 48 ? sanitized : sanitized.substring(0, 48);
    }

    private static final class Session {
        private final String url;
        private final ArrayBlockingQueue<LiveViewFrame> latest = new ArrayBlockingQueue<>(1);
        private final Semaphore available = new Semaphore(0);
        private final CountDownLatch finished = new CountDownLatch(1);
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean deleteSent = new AtomicBoolean();
        private volatile Thread worker;
        private volatile InputStream input;
        private volatile RuntimeException failure;

        private Session(String url) {
            this.url = url;
        }

        private void publish(LiveViewFrame frame) {
            latest.poll();
            latest.offer(frame);
            available.drainPermits();
            available.release();
        }

        private void fail(RuntimeException failure) {
            this.failure = failure;
            available.release();
        }
    }

    private static final class RestTemplateTransport implements Transport {
        private final RestTemplate restTemplate;

        private RestTemplateTransport(RestTemplate restTemplate) {
            this.restTemplate = restTemplate;
        }

        @Override
        public void read(String url, StreamHandler handler) {
            restTemplate.execute(
                    URI.create(url),
                    HttpMethod.GET,
                    null,
                    response -> {
                        handler.read(response.getBody());
                        return null;
                    });
        }

        @Override
        public void cancel(String url) {
            restTemplate.delete(url);
        }
    }
}
