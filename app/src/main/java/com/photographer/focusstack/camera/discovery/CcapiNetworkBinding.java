package com.photographer.focusstack.camera.discovery;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Objects;

/** One eligible local interface/address pair for a single SSDP address family. */
record CcapiNetworkBinding(
        NetworkInterface networkInterface,
        InetAddress localAddress,
        CcapiAddressFamily family
) {
    CcapiNetworkBinding {
        networkInterface = Objects.requireNonNull(networkInterface, "networkInterface");
        localAddress = Objects.requireNonNull(localAddress, "localAddress");
        family = Objects.requireNonNull(family, "family");
        if (family != CcapiAddressFamily.of(localAddress)) {
            throw new IllegalArgumentException("Address family does not match local address");
        }
    }

    String displayName() {
        String display = networkInterface.getDisplayName();
        return display == null || display.isBlank() ? networkInterface.getName() : display;
    }
}
