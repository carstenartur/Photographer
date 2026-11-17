package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.camera.exception.CameraOperationException;
import com.photographer.focusstack.common.camera.CameraCapabilitySnapshot;
import com.photographer.focusstack.common.camera.CameraMediaProvider;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.ENDPOINT_NOT_FOUND;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.METHOD_NOT_ALLOWED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonCcapiMediaOperationTest {

    @Test
    void legacyReadsNeedAnExactPathAndCancellationNeedsExplicitMethodsOnOneVersion() {
        String base = "http://camera.example";
        var catalog = CcapiEndpointCatalogTest.catalog(base, Map.of(
                "ver100", List.of(Map.of("path", "/ccapi/ver100/event/polling", "get", true)),
                "ver110", List.of(Map.of("path", "/ccapi/ver110/event/polling", "delete", true)),
                "ver120", List.of("/ccapi/ver120/contents", "/ccapi/ver120/event/polling"),
                "ver140", List.of(Map.of("path", "/ccapi/ver140/vendor/event/monitoring", "get", true, "delete", true),
                        Map.of("path", "/ccapi/ver140/event%2Fmonitoring", "get", true, "delete", true),
                        Map.of("url", "http://different.example/ccapi/ver140/event/monitoring", "get", true, "delete", true))));

        assertEquals(base + "/ccapi/ver120/contents", CcapiMediaOperation.CONTENTS.requireUrl(catalog, "camera"));
        assertEquals(base + "/ccapi/ver120/event/polling", CcapiMediaOperation.POLLING.requireUrl(catalog, "camera"));
        assertEquals(METHOD_NOT_ALLOWED, assertThrows(CameraOperationException.class,
                () -> CcapiMediaOperation.POLLING_CONTINUATION.requireUrl(catalog, "camera")).reason());
        assertEquals(ENDPOINT_NOT_FOUND, assertThrows(CameraOperationException.class,
                () -> CcapiMediaOperation.MONITORING.requireUrl(catalog, "camera")).reason());
    }

    @Test
    void productionTransportAndDiagnosticsUseTheSameEarlierEligibleEndpoints() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> calls = new CopyOnWriteArrayList<>();
        server.createContext("/", exchange -> {
            String target = exchange.getRequestMethod() + " " + exchange.getRequestURI();
            calls.add(target);
            String body = switch (target) {
                case "GET /ccapi" -> """
                        {"ver100":[
                           {"path":"/ccapi/ver100/deviceinformation","get":true},
                           {"path":"/ccapi/ver100/contents","get":true},
                           {"path":"/ccapi/ver100/devicestatus/lens","get":true},
                           {"path":"/ccapi/ver100/event/polling","get":true,"delete":true},
                           {"path":"/ccapi/ver100/event/monitoring","get":true,"delete":true}],
                         "ver110":[
                           {"path":"/ccapi/ver110/deviceinformation","post":true},
                           {"path":"/ccapi/ver110/contents","post":true},
                           {"path":"/ccapi/ver110/devicestatus/lens"},
                           {"path":"/ccapi/ver110/event/polling","delete":true},
                           {"path":"/ccapi/ver110/event/monitoring","get":true}],
                         "ver140":[
                           {"path":"/ccapi/ver140/vendor/deviceinformation","get":true},
                           {"path":"/ccapi/ver140/nested/contents","get":true},
                           {"path":"/ccapi/ver140/vendor/event/polling","get":true,"delete":true},
                           {"path":"/ccapi/ver140/vendor/event/monitoring","get":true,"delete":true}]}
                        """;
                case "GET /ccapi/ver100/deviceinformation" -> "{\"productname\":\"test camera\"}";
                case "GET /ccapi/ver100/devicestatus/lens" -> "{\"mount\":true,\"name\":\"test lens\"}";
                case "GET /ccapi/ver100/contents" -> "{\"path\":[]}";
                case "GET /ccapi/ver100/event/polling", "GET /ccapi/ver100/event/polling?continue=on" ->
                        "{\"addedcontents\":[\"/ccapi/ver100/contents/sd/100CANON/ONE.JPG\"]}";
                case "GET /ccapi/ver100/event/monitoring" -> "";
                case "DELETE /ccapi/ver100/event/polling", "DELETE /ccapi/ver100/event/monitoring" -> "{}";
                default -> null;
            };
            try (exchange) {
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var backend = new CanonCcapiProductionBackend("camera", base, new RestTemplate());
            var snapshot = backend.getCapabilitySnapshot();
            assertEquals("test camera", snapshot.model());
            for (String id : List.of("device-information", "lens-status", "media-list-and-download", "event-polling",
                    "event-polling-continuation", "event-monitoring")) {
                assertEquals(CameraCapabilitySnapshot.Availability.AVAILABLE, snapshot.capabilities().stream()
                        .filter(capability -> capability.id().equals(id)).findFirst().orElseThrow().availability(), id);
            }
            assertTrue(backend.listMedia(CameraMediaProvider.MediaQuery.bounded(2)).items().isEmpty());
            for (var mode : List.of(CameraMediaProvider.EventMode.POLLING, CameraMediaProvider.EventMode.POLLING_CONTINUATION)) {
                assertEquals("ONE.JPG", backend.awaitAddedMedia(mode, Duration.ofSeconds(3)).orElseThrow().fileName());
            }
            assertTrue(backend.awaitAddedMedia(CameraMediaProvider.EventMode.MONITORING, Duration.ofSeconds(3)).isEmpty());
            assertEquals(List.of("GET /ccapi", "GET /ccapi/ver100/deviceinformation", "GET /ccapi/ver100/devicestatus/lens",
                    "GET /ccapi/ver100/contents",
                    "GET /ccapi/ver100/event/polling", "GET /ccapi/ver100/event/polling?continue=on",
                    "DELETE /ccapi/ver100/event/polling", "GET /ccapi/ver100/event/monitoring",
                    "DELETE /ccapi/ver100/event/monitoring"), calls);
        } finally {
            server.stop(0);
        }
    }
}
