package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.camera.exception.CameraException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Objects;

/**
 * Bounded one-frame consumer for Canon's long-lived {@code /liveview/scroll} response.
 *
 * <p>The HTTP layer removes transfer-chunk framing before exposing the response stream,
 * so frame boundaries are recovered from JPEG SOI/EOI markers. The consumer retains
 * only one frame, has no queue, and returns immediately after the first complete JPEG.
 * The owning live-view client is responsible for the matching DELETE request.</p>
 */
final class CcapiScrollFrameTransport {

    static final int DEFAULT_MAX_PREAMBLE_BYTES = 64 * 1024;
    static final int DEFAULT_MAX_FRAME_BYTES = 4 * 1024 * 1024;

    private final RestTemplate restTemplate;
    private final int maximumPreambleBytes;
    private final int maximumFrameBytes;

    CcapiScrollFrameTransport(RestTemplate restTemplate) {
        this(restTemplate, DEFAULT_MAX_PREAMBLE_BYTES, DEFAULT_MAX_FRAME_BYTES);
    }

    CcapiScrollFrameTransport(
            RestTemplate restTemplate,
            int maximumPreambleBytes,
            int maximumFrameBytes) {
        this.restTemplate = Objects.requireNonNull(restTemplate, "restTemplate");
        this.maximumPreambleBytes = requirePositive(maximumPreambleBytes, "maximumPreambleBytes");
        this.maximumFrameBytes = requirePositive(maximumFrameBytes, "maximumFrameBytes");
    }

    byte[] readFirstFrame(String url) {
        byte[] frame = restTemplate.execute(
                Objects.requireNonNull(url, "url"),
                HttpMethod.GET,
                null,
                response -> {
                    MediaType contentType = response.getHeaders().getContentType();
                    if (!isOctetStream(contentType)) {
                        throw new CameraException(
                                "Canon scroll live view returned an unsupported media type");
                    }
                    return readFirstJpeg(
                            response.getBody(), maximumPreambleBytes, maximumFrameBytes);
                });
        if (frame == null || frame.length == 0) {
            throw new CameraException("Canon scroll live view returned no JPEG frame");
        }
        return frame;
    }

    void stop(String url) {
        restTemplate.delete(Objects.requireNonNull(url, "url"));
    }

    static byte[] readFirstJpeg(
            InputStream input,
            int maximumPreambleBytes,
            int maximumFrameBytes) throws IOException {
        Objects.requireNonNull(input, "input");
        requirePositive(maximumPreambleBytes, "maximumPreambleBytes");
        requirePositive(maximumFrameBytes, "maximumFrameBytes");

        ByteArrayOutputStream frame = new ByteArrayOutputStream(
                Math.min(maximumFrameBytes, 256 * 1024));
        int previous = -1;
        int preambleBytes = 0;
        boolean started = false;

        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("scroll live-view read was interrupted");
            }
            int current = input.read();
            if (current < 0) {
                throw new EOFException(started
                        ? "scroll live-view response ended before JPEG EOI"
                        : "scroll live-view response contained no JPEG SOI");
            }

            if (!started) {
                preambleBytes++;
                if (previous == 0xff && current == 0xd8) {
                    started = true;
                    frame.write(0xff);
                    frame.write(0xd8);
                    previous = current;
                    continue;
                }
                if (preambleBytes > maximumPreambleBytes) {
                    throw new IOException("scroll live-view JPEG preamble exceeds limit");
                }
                previous = current;
                continue;
            }

            frame.write(current);
            if (frame.size() > maximumFrameBytes) {
                throw new IOException("scroll live-view JPEG exceeds frame limit");
            }
            if (previous == 0xff && current == 0xd9) {
                return frame.toByteArray();
            }
            previous = current;
        }
    }

    private static boolean isOctetStream(MediaType contentType) {
        if (contentType == null) {
            return false;
        }
        return "octet-stream".equalsIgnoreCase(contentType.getSubtype())
                && ("image".equalsIgnoreCase(contentType.getType())
                || "application".equalsIgnoreCase(contentType.getType()));
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
