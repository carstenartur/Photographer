package com.photographer.focusstack.camera.discovery;

import java.net.SocketException;
import java.util.List;

/** Supplies eligible local network bindings without assuming an interface name. */
@FunctionalInterface
interface CcapiNetworkBindingProvider {
    List<CcapiNetworkBinding> bindings() throws SocketException;
}
