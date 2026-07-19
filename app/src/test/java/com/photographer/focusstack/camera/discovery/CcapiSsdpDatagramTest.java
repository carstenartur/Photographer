package com.photographer.focusstack.camera.discovery;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CcapiSsdpDatagramTest {

    @Test
    void defensivelyCopiesPayloadOnConstructionAndAccess() {
        byte[] sourcePayload = {1, 2, 3};
        InetSocketAddress source = new InetSocketAddress("127.0.0.1", 1900);
        CcapiSsdpDatagram datagram = new CcapiSsdpDatagram(sourcePayload, source);

        sourcePayload[0] = 9;
        byte[] firstRead = datagram.payload();
        firstRead[1] = 8;

        assertArrayEquals(new byte[]{1, 2, 3}, datagram.payload());
        assertEquals(source, datagram.source());
    }

    @Test
    void rejectsMissingPayloadOrSource() {
        InetSocketAddress source = new InetSocketAddress("127.0.0.1", 1900);
        assertThrows(NullPointerException.class,
                () -> new CcapiSsdpDatagram(null, source));
        assertThrows(NullPointerException.class,
                () -> new CcapiSsdpDatagram(new byte[0], null));
    }
}
