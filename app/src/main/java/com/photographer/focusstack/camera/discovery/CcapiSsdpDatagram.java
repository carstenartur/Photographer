package com.photographer.focusstack.camera.discovery;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Objects;

/** Received SSDP payload together with its network source for LOCATION validation. */
record CcapiSsdpDatagram(byte[] payload, InetSocketAddress source) {
    CcapiSsdpDatagram {
        payload = Arrays.copyOf(Objects.requireNonNull(payload, "payload"), payload.length);
        source = Objects.requireNonNull(source, "source");
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }
}
