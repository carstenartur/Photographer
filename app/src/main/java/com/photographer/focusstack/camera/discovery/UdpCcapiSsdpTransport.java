package com.photographer.focusstack.camera.discovery;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** JDK multicast transport for Canon SSDP M-SEARCH over IPv4 and IPv6. */
final class UdpCcapiSsdpTransport implements CcapiSsdpTransport {

    private static final int RECEIVE_BUFFER_SIZE = 16 * 1024;
    private static final int MAX_PACKETS_PER_BINDING = 64;

    @Override
    public List<CcapiSsdpDatagram> search(
            CcapiNetworkBinding binding, byte[] request, Duration timeout)
            throws IOException {
        if (request == null || request.length == 0) {
            throw new IllegalArgumentException("request must not be empty");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }

        long deadlineNanos;
        try {
            deadlineNanos = Math.addExact(System.nanoTime(), timeout.toNanos());
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("timeout is too large", exception);
        }

        List<CcapiSsdpDatagram> responses = new ArrayList<>();
        try (MulticastSocket socket = new MulticastSocket(null)) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(binding.localAddress(), 0));
            socket.setNetworkInterface(binding.networkInterface());

            InetSocketAddress target = binding.family().target(binding.networkInterface());
            socket.send(new DatagramPacket(request, request.length, target));

            while (responses.size() < MAX_PACKETS_PER_BINDING) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0L) {
                    break;
                }
                long remainingMillis = Math.max(1L, Duration.ofNanos(remainingNanos).toMillis());
                socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, remainingMillis));

                byte[] buffer = new byte[RECEIVE_BUFFER_SIZE];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                    if (packet.getSocketAddress() instanceof InetSocketAddress source) {
                        responses.add(new CcapiSsdpDatagram(
                                Arrays.copyOf(packet.getData(), packet.getLength()), source));
                    }
                } catch (SocketTimeoutException timeoutException) {
                    break;
                }
            }
        }
        return List.copyOf(responses);
    }
}
