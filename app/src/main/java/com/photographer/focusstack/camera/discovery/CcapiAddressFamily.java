package com.photographer.focusstack.camera.discovery;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;

/** IP families and multicast targets defined by Canon's UPnP/SSDP discovery profile. */
public enum CcapiAddressFamily {
    IPV4("239.255.255.250", "239.255.255.250:1900"),
    IPV6("FF02::C", "[FF02::C]:1900");

    static final int SSDP_PORT = 1900;

    private final String multicastAddress;
    private final String hostHeader;

    CcapiAddressFamily(String multicastAddress, String hostHeader) {
        this.multicastAddress = multicastAddress;
        this.hostHeader = hostHeader;
    }

    String hostHeader() {
        return hostHeader;
    }

    InetSocketAddress target(NetworkInterface networkInterface) throws UnknownHostException {
        InetAddress address = InetAddress.getByName(multicastAddress);
        if (this == IPV6 && address instanceof Inet6Address ipv6 && networkInterface != null) {
            address = Inet6Address.getByAddress(
                    null, ipv6.getAddress(), networkInterface.getIndex());
        }
        return new InetSocketAddress(address, SSDP_PORT);
    }

    public static CcapiAddressFamily of(InetAddress address) {
        return address.getAddress().length == 4 ? IPV4 : IPV6;
    }
}
