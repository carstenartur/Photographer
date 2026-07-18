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
    }

    String displayName() {
        String display = networkInterface.getDisplayName();
        return display == null || display.isBlank() ? networkInterface.getName() : display;
    }
}
