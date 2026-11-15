package com.photographer.focusstack.camera.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CcapiEvidenceTest {

    @Test
    void exportsBothVersionsAndOnlyReadOnlyPublicEvidence() throws Exception {
        String origin = "http://camera.invalid";
        RestTemplate rest = new RestTemplate();
        var server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo(origin + "/ccapi")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                    {"ver100":[
                      {"url":"http://camera.invalid/ccapi/ver100/deviceinformation","get":true},
                      {"url":"http://camera.invalid/ccapi/ver100/contents/private-photo.jpg","get":true}
                    ],"ver110":[
                      {"path":"/ccapi/ver110/shooting/liveview/scroll","get":true,"delete":true},
                      {"path":"/ccapi/ver110/shooting/liveview/multipart","get":true,"delete":true},
                      {"path":"/ccapi/ver110/shooting/settings/focusbracketing/numberofshots","get":true,"put":true}
                    ]}
                    """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(origin + "/ccapi/ver100/deviceinformation")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                    {"productname":"EOS R","firmwareversion":"1.8.0","serialnumber":"secret-serial",
                     "ownername":"private-name","guid":"private-guid"}
                    """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(origin + "/ccapi/ver110/shooting/settings/focusbracketing/numberofshots"))
                .andExpect(method(HttpMethod.GET)).andRespond(withSuccess("""
                    {"value":20,"ability":{"min":2,"max":999,"step":1}}
                    """, MediaType.APPLICATION_JSON));

        String json = CcapiEvidence.collect(origin, rest, CcapiEvidence.ConnectionMode.WIFI_INFRASTRUCTURE);
        var evidence = new ObjectMapper().readTree(json);
        assertEquals("EOS R", evidence.path("model").asText());
        assertEquals("1.8.0", evidence.path("firmwareVersion").asText());
        assertEquals("WIFI_INFRASTRUCTURE", evidence.path("connectionMode").asText());
        assertEquals("ver100", evidence.path("endpoints").get(0).path("version").asText());
        assertEquals(4, evidence.path("endpoints").size());
        assertEquals(1, evidence.path("omittedEndpoints").asInt());
        assertEquals(999, evidence.path("nativeFocusBracketing").path("numberOfShots").path("max").asInt());
        assertEquals("AVAILABLE", evidence.path("capabilities").findParents("id").stream()
                        .filter(node -> "live-view-scroll".equals(node.path("id").asText()))
                        .findFirst().orElseThrow().path("availability").asText());
        for (String privateValue : new String[]{"camera.invalid", "private-photo", "secret-serial",
                "private-name", "private-guid"}) {
            assertFalse(json.contains(privateValue));
        }
        server.verify();
    }

    @Test
    void productionTransportExportsIdenticalJsonAndIgnoresPrivateCameraData() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> calls = new CopyOnWriteArrayList<>();
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            calls.add(exchange.getRequestMethod() + " " + path);
            String body = switch (path) {
                case "/ccapi" -> """
                        {"ver100":[{"path":"/ccapi/ver100/deviceinformation","get":true}],
                         "ver110":[{"path":"/ccapi/ver110/contents/private-file","get":true}]}
                        """;
                case "/ccapi/ver100/deviceinformation" -> """
                        {"productname":"EOS R","firmwareversion":"1.8.0","serialnumber":"private-serial"}
                        """;
                default -> throw new AssertionError("Unexpected diagnostic operation");
            };
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var response = exchange.getResponseBody()) {
                response.write(bytes);
            }
        });
        server.start();
        try {
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            String first = CcapiEvidence.capture(origin, CcapiEvidence.ConnectionMode.ETHERNET, null, null);
            assertEquals(first, CcapiEvidence.capture(origin, CcapiEvidence.ConnectionMode.ETHERNET, null, null));
            assertFalse(first.contains("127.0.0.1"));
            assertFalse(first.contains("private-"));
            assertEquals(List.of("GET /ccapi", "GET /ccapi/ver100/deviceinformation",
                    "GET /ccapi", "GET /ccapi/ver100/deviceinformation"), calls);
        } finally {
            server.stop(0);
        }
    }
}
