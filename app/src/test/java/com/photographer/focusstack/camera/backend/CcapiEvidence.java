package com.photographer.focusstack.camera.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.photographer.focusstack.app.config.CameraConfig;
import com.photographer.focusstack.app.config.CcapiAuthenticationProperties;
import com.photographer.focusstack.common.camera.CameraCapabilitySnapshot;
import com.photographer.focusstack.common.camera.IntegerSettingRange;
import com.photographer.focusstack.common.model.CameraProfile;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Read-only hardware evidence produced by the same backend as a Canon CCAPI profile. */
public final class CcapiEvidence {
    public enum ConnectionMode { WIFI_INFRASTRUCTURE, WIFI_CAMERA_ACCESS_POINT, ETHERNET }

    private static final Pattern DIAGNOSTIC_GET = Pattern.compile(
            "/ccapi(?:/ver[0-9]+(?:/topurlfordev|/deviceinformation|/devicestatus/lens"
                    + "|/shooting/settings/focusbracketing(?:/numberofshots|/focusincrement|/exposuresmoothing"
                    + "|/depthcomposite|/cropdepthcomp)?)?)?");

    private CcapiEvidence() {
    }

    public static String capture(String baseUrl, ConnectionMode mode, String username, String password)
            throws IOException {
        URI base = CcapiRestTemplateFactory.normalizeBaseUri(URI.create(baseUrl));
        if (!base.getPath().isEmpty()) {
            throw new IllegalArgumentException("Use the camera base URL without a path");
        }
        var profile = new CameraProfile("evidence", "Evidence", "canon-ccapi", base.getHost(), 1, 1, 0);
        var authentication = new CcapiAuthenticationProperties();
        if (username != null || password != null) {
            var credential = new CcapiAuthenticationProperties.Credential();
            credential.setUsername(username);
            credential.setPassword(password);
            authentication.setCredentials(Map.of(profile.id(), credential));
        }
        var config = new CameraConfig(base, false, Duration.ofSeconds(5));
        var metrics = new SimpleMeterRegistry();
        try (var transport = new CcapiRestTemplateFactory(config, authentication, new CcapiOutcomeRecorder(metrics))
                     .create(profile, base)) {
            var rest = transport.restTemplate();
            rest.getInterceptors().add(0, (request, body, execution) -> {
                URI uri = request.getURI();
                if (request.getMethod() != HttpMethod.GET || !base.resolve("/").equals(uri.resolve("/"))
                        || !DIAGNOSTIC_GET.matcher(uri.getRawPath()).matches()
                        || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                    throw new IllegalStateException("Unrecognized evidence request");
                }
                return execution.execute(request, body);
            });
            return collect(base.toString(), rest, mode);
        } finally {
            metrics.close();
        }
    }

    static String collect(String origin, RestTemplate rest, ConnectionMode mode) throws IOException {
        var backend = new CanonCcapiProductionBackend("evidence", origin, rest);
        CameraCapabilitySnapshot snapshot = backend.getCapabilitySnapshot();
        if (!snapshot.catalogAvailable()) {
            throw new IllegalStateException("Camera returned no usable endpoint catalog");
        }
        var capabilities = backend.getNativeFocusBracketingCapabilities();
        var ranges = new Bracketing(capabilities.bracketingSupported(), capabilities.numberOfShots().orElse(null),
                capabilities.focusIncrement().orElse(null), capabilities.exposureSmoothingSupported(),
                capabilities.depthCompositionSupported(), capabilities.cropDepthCompositionSupported());
        var endpoints = snapshot.advertisedEndpoints().stream()
                .filter(endpoint -> CanonCcapiCapabilitySnapshotFactory.isDocumentedEvidencePath(endpoint.path()))
                .toList();
        var evidence = new Evidence(1, snapshot.model(), snapshot.firmwareVersion(), mode, endpoints,
                snapshot.advertisedEndpoints().size() - endpoints.size(), snapshot.capabilities(), ranges);
        return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(evidence)
                .replace("\r\n", "\n") + "\n";
    }

    private record Evidence(int schemaVersion, String model, String firmwareVersion, ConnectionMode connectionMode,
                            List<CameraCapabilitySnapshot.Endpoint> endpoints, int omittedEndpoints,
                            List<CameraCapabilitySnapshot.Capability> capabilities, Bracketing nativeFocusBracketing) {
    }

    private record Bracketing(boolean bracketingSupported, IntegerSettingRange numberOfShots,
                              IntegerSettingRange focusIncrement, boolean exposureSmoothingSupported,
                              boolean depthCompositionSupported, boolean cropDepthCompositionSupported) {
    }
}
