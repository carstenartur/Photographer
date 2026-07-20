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

    private static final String SHUTTER_SUFFIX = "/shooting/control/shutterbutton";
    private static final Pattern VERSION_PATTERN = Pattern.compile("(?:^|/)ver(\\d+)(?:/|$)");

    private CanonCcapiNativeSequenceTrigger() {
    }

    static void trigger(CanonCcapiNativeFocusBracketingBackend backend) {
        String shutterUrl = exactPostEndpoint(backend);
        backend.stopLiveView();
        backend.ccapiPost(shutterUrl, Map.of("af", false));
    }

    private static String exactPostEndpoint(
            CanonCcapiNativeFocusBracketingBackend backend) {
        Map<String, String> endpoints = backend.getDiscoveredEndpoints();
        Map<String, Set<HttpMethod>> methods = backend.getDiscoveredEndpointMethods();
        return endpoints.entrySet().stream()
                .map(entry -> candidate(entry, methods.get(entry.getKey())))
                .filter(candidate -> candidate.path().endsWith(SHUTTER_SUFFIX))
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
            Set<HttpMethod> methods) {
        String path;
        try {
            path = URI.create(entry.getValue()).getPath();
        } catch (IllegalArgumentException exception) {
            path = null;
        }
        return new Candidate(
                path == null ? "" : path,
                entry.getValue(),
                versionNumber(path),
                methods);
    }

    private static int versionNumber(String path) {
        if (path == null) {
            return -1;
        }
        Matcher matcher = VERSION_PATTERN.matcher(path);
        if (!matcher.find()) {
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
