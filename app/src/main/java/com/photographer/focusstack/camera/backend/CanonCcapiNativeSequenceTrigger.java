package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.common.camera.NativeFocusBracketingTransportException;
import org.springframework.http.HttpMethod;

import java.util.Map;
import java.util.Set;

import static com.photographer.focusstack.common.camera.NativeFocusBracketingTransportException.Reason.SHUTTER_UNAVAILABLE;

/** Sends exactly one shutter POST for a prepared camera-owned native sequence. */
final class CanonCcapiNativeSequenceTrigger {

    private CanonCcapiNativeSequenceTrigger() {
    }

    static void trigger(CanonCcapiNativeFocusBracketingBackend backend) {
        String shutterUrl = shutterEndpoint(backend.endpointCatalog()).url();
        backend.stopLiveView();
        backend.ccapiPostOnce(shutterUrl, Map.of("af", false));
    }

    static CcapiEndpointCatalog.Endpoint shutterEndpoint(CcapiEndpointCatalog catalog) {
        return catalog.findOperation("/shooting/control/shutterbutton", Set.of(HttpMethod.POST), false)
                .orElseThrow(() -> new NativeFocusBracketingTransportException(SHUTTER_UNAVAILABLE));
    }
}
