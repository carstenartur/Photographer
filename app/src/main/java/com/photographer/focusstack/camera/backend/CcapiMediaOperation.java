package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.camera.exception.CameraOperationException;
import org.springframework.http.HttpMethod;

import java.util.Optional;
import java.util.Set;

import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.ENDPOINT_NOT_FOUND;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.METHOD_NOT_ALLOWED;

/** Methods required on one advertised endpoint for each media/event transport. */
enum CcapiMediaOperation {
    CONTENTS("media-list-and-download", "/contents", true, HttpMethod.GET),
    POLLING("event-polling", "/event/polling", true, HttpMethod.GET),
    POLLING_CONTINUATION("event-polling-continuation", "/event/polling", false, HttpMethod.GET, HttpMethod.DELETE),
    MONITORING("event-monitoring", "/event/monitoring", false, HttpMethod.GET, HttpMethod.DELETE);

    final String capability;
    final String path;
    final boolean allowUnknownMethods;
    final Set<HttpMethod> methods;

    CcapiMediaOperation(String capability, String path, boolean allowUnknownMethods, HttpMethod... methods) {
        this.capability = capability;
        this.path = path;
        this.allowUnknownMethods = allowUnknownMethods;
        this.methods = Set.of(methods);
    }

    Optional<CcapiEndpointCatalog.Endpoint> select(CcapiEndpointCatalog catalog) {
        return catalog.findOperation(path, methods, allowUnknownMethods);
    }

    String requireUrl(CcapiEndpointCatalog catalog, String profileId) {
        return select(catalog).orElseThrow(() -> catalog.findOperation(path, Set.of(), true).isPresent()
                ? new CameraOperationException(profileId, METHOD_NOT_ALLOWED, 405)
                : new CameraOperationException(profileId, ENDPOINT_NOT_FOUND, 404)).url();
    }
}
