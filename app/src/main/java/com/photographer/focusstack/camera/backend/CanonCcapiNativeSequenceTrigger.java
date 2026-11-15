package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.camera.exception.CameraException;
import org.springframework.http.HttpMethod;

import java.net.URI;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Sends exactly one shutter POST for a prepared camera-owned native sequence. */
final class CanonCcapiNativeSequenceTrigger {

    private static final String SHUTTER_PATH = "/ccapi/ver(\\d+)/shooting/control/shutterbutton";

    private CanonCcapiNativeSequenceTrigger() {
    }

    static void trigger(CanonCcapiNativeFocusBracketingBackend backend) {
        String shutterUrl = exactPostEndpoint(backend);
        backend.stopLiveView();
        backend.ccapiPostOnce(shutterUrl, Map.of("af", false));
    }

    private static String exactPostEndpoint(
            CanonCcapiNativeFocusBracketingBackend backend) {
        Map<String, String> endpoints = backend.getDiscoveredEndpoints();
        Map<String, Set<HttpMethod>> methods = backend.getDiscoveredEndpointMethods();
        String basePath = URI.create(backend.ccapiBaseUrl()).getRawPath();
        String prefix = basePath == null ? "" : basePath.replaceFirst("/+$", "");
        String optionalPrefix = prefix.isEmpty() ? "" : "(?:" + Pattern.quote(prefix) + ")?";
        Pattern shutterPath = Pattern.compile(optionalPrefix + SHUTTER_PATH);
        return endpoints.entrySet().stream()
                .map(entry -> candidate(entry, methods.get(entry.getKey()), shutterPath))
                .filter(candidate -> candidate.versionNumber() >= 0)
                .filter(candidate -> candidate.methods() != null
                        && candidate.methods().contains(HttpMethod.POST))
                .max(Comparator.comparingInt(Candidate::versionNumber)
                        .thenComparing(Candidate::path))
                .map(Candidate::url)
                .orElseThrow(() -> new CameraException(
                        "Native focus bracketing requires an exact advertised shutterbutton POST endpoint"));
    }

    private static Candidate candidate(
            Map.Entry<String, String> entry,
            Set<HttpMethod> methods,
            Pattern shutterPath) {
        String path;
        try {
            path = URI.create(entry.getValue()).getRawPath();
        } catch (IllegalArgumentException exception) {
            path = null;
        }
        return new Candidate(
                path == null ? "" : path,
                entry.getValue(),
                versionNumber(path, shutterPath),
                methods);
    }

    private static int versionNumber(String path, Pattern shutterPath) {
        if (path == null) {
            return -1;
        }
        Matcher matcher = shutterPath.matcher(path);
        if (!matcher.matches()) {
            return -1;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private record Candidate(
            String path,
            String url,
            int versionNumber,
            Set<HttpMethod> methods) {
    }
}
