package com.photographer.focusstack.camera.discovery;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/** Sends one M-SEARCH request on a selected binding and returns received packets. */
@FunctionalInterface
interface CcapiSsdpTransport {
    List<byte[]> search(CcapiNetworkBinding binding, byte[] request, Duration timeout)
            throws IOException;
}
