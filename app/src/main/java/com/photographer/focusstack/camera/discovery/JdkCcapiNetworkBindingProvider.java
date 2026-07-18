package com.photographer.focusstack.camera.discovery;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Enumerates every up, non-loopback, multicast-capable interface at runtime. */
final class JdkCcapiNetworkBindingProvider implements CcapiNetworkBindingProvider {

    @Override
    public List<CcapiNetworkBinding> bindings() throws SocketException {
        List<CcapiNetworkBinding> result = new ArrayList<>();
        for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!isEligible(networkInterface)) {
                continue;
            }
            for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isMulticastAddress()) {
                    continue;
                }
                result.add(new CcapiNetworkBinding(
                        networkInterface, address, CcapiAddressFamily.of(address)));
            }
        }
        return List.copyOf(result);
    }

    private static boolean isEligible(NetworkInterface networkInterface) throws SocketException {
        return networkInterface.isUp()
                && !networkInterface.isLoopback()
                && networkInterface.supportsMulticast();
    }
}
