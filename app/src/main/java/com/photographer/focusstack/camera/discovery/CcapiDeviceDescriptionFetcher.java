package com.photographer.focusstack.camera.discovery;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;

/** Retrieves one bounded UPnP device-description document. */
@FunctionalInterface
interface CcapiDeviceDescriptionFetcher {
    byte[] fetch(URI descriptionUrl, Duration timeout) throws IOException, InterruptedException;
}
