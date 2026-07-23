package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.camera.exception.CameraException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CcapiScrollFrameTransportTest {

    private static final String SCROLL =
            "http://camera.example/ccapi/ver100/shooting/liveview/scroll";

    @Test
    void extractsOnlyTheFirstCompleteJpegAndStopsWithDelete() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        byte[] first = {
                (byte) 0xff, (byte) 0xd8, 1, 2, (byte) 0xff, 0, 3, (byte) 0xff, (byte) 0xd9
        };
        byte[] second = {
                (byte) 0xff, (byte) 0xd8, 9, (byte) 0xff, (byte) 0xd9
        };
        byte[] response = concat(new byte[]{0, 1, 2}, first, second);

        server.expect(once(), requestTo(SCROLL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(response, MediaType.parseMediaType("image/octet-stream")));
        server.expect(once(), requestTo(SCROLL))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        CcapiScrollFrameTransport transport = new CcapiScrollFrameTransport(restTemplate);
        byte[] actual = transport.readFirstFrame(SCROLL);
        transport.stop(SCROLL);

        assertThat(actual).containsExactly(first);
        server.verify();
    }

    @Test
    void acceptsApplicationOctetStreamForFirmwareCompatibility() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        byte[] jpeg = {(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, (byte) 0xd9};

        server.expect(requestTo(SCROLL))
                .andRespond(withSuccess(jpeg, MediaType.APPLICATION_OCTET_STREAM));

        assertThat(new CcapiScrollFrameTransport(restTemplate).readFirstFrame(SCROLL))
                .containsExactly(jpeg);
        server.verify();
    }

    @Test
    void rejectsUnexpectedContentTypeBeforeBufferingTheStream() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo(SCROLL))
                .andRespond(withSuccess("not-an-image", MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> new CcapiScrollFrameTransport(restTemplate)
                .readFirstFrame(SCROLL))
                .isInstanceOf(CameraException.class)
                .hasMessageContaining("unsupported media type");
        server.verify();
    }

    @Test
    void rejectsPreambleAndFrameGrowthBeyondConfiguredLimits() {
        assertThatThrownBy(() -> CcapiScrollFrameTransport.readFirstJpeg(
                new ByteArrayInputStream(new byte[]{0, 1, 2, 3}),
                3,
                16))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("preamble");

        byte[] oversized = {
                (byte) 0xff, (byte) 0xd8, 1, 2, 3, 4, 5, (byte) 0xff, (byte) 0xd9
        };
        assertThatThrownBy(() -> CcapiScrollFrameTransport.readFirstJpeg(
                new ByteArrayInputStream(oversized),
                16,
                6))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("frame limit");
    }

    @Test
    void preservesThreadInterruptionAsCancellationSignal() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> CcapiScrollFrameTransport.readFirstJpeg(
                    new ByteArrayInputStream(new byte[]{(byte) 0xff, (byte) 0xd8}),
                    16,
                    16))
                    .isInstanceOf(InterruptedIOException.class);
        } finally {
            Thread.interrupted();
        }
    }

    private static byte[] concat(byte[]... arrays) {
        int length = 0;
        for (byte[] array : arrays) {
            length += array.length;
        }
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }
}
