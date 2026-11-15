package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.app.config.CameraConfig;
import com.photographer.focusstack.app.config.CcapiAuthenticationProperties;
import com.photographer.focusstack.camera.exception.CameraOperationException;
import com.photographer.focusstack.common.model.CameraProfile;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CanonCcapiNativeSequenceTriggerTest {

    @Test
    void sendsExactlyOnePostToHighestCompatibleAdvertisedShutterEndpoint() {
        String baseUrl = "http://camera.example:8080";
        String compatible = baseUrl + "/ccapi/ver100/shooting/control/shutterbutton";
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo(baseUrl + "/ccapi"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "ver100":[
                            {"url":"http://camera.example:8080/ccapi/ver100/shooting/control/shutterbutton",
                             "get":false,"post":true,"put":false,"delete":false}
                          ],
                          "ver140":[
                            {"path":"/ccapi/ver140/shooting/control/shutterbutton",
                             "get":true,"post":false,"put":false,"delete":false}
                          ],
                          "ver150":[
                            {"path":"/ccapi/ver150/vendor/shooting/control/shutterbutton",
                             "get":false,"post":true,"put":false,"delete":false}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(compatible))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string("{\"af\":false}"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        CanonCcapiNativeFocusBracketingBackend backend =
                new CanonCcapiNativeFocusBracketingBackend(baseUrl, restTemplate);
        CanonCcapiNativeSequenceTrigger.trigger(backend);

        server.verify();
    }

    @Test
    void productionTransportRetriesCatalogReadButDoesNotResendBusyShutter() throws IOException {
        String shutterPath = "/ccapi/ver140/shooting/control/shutterbutton";
        AtomicInteger catalogReads = new AtomicInteger();
        AtomicInteger shutterPosts = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ccapi", exchange -> {
            if (catalogReads.incrementAndGet() == 1) {
                respond(exchange, 503, "{\"message\":\"Device busy\"}");
            } else {
                respond(exchange, 200, """
                        {"ver140":[{"path":"/ccapi/ver140/shooting/control/shutterbutton","post":true}]}
                        """);
            }
        });
        server.createContext(shutterPath, exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "{}");
            } else if (shutterPosts.incrementAndGet() == 1) {
                respond(exchange, 503, "{\"message\":\"Device busy\"}");
            } else {
                respond(exchange, 200, "{}");
            }
        });
        server.start();

        var meterRegistry = new SimpleMeterRegistry();
        try {
            URI baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var profile = new CameraProfile("camera-profile", "Test camera", "canon-ccapi",
                    "127.0.0.1", 1, 20, 0.1);
            var factory = new CcapiRestTemplateFactory(
                    new CameraConfig(baseUri, false, Duration.ofSeconds(2)),
                    new CcapiAuthenticationProperties(),
                    new CcapiOutcomeRecorder(meterRegistry));
            try (var transport = factory.create(profile, baseUri)) {
                var backend = new CanonCcapiNativeFocusBracketingBackend(
                        baseUri.toString(), transport.restTemplate());

                var failure = assertThrows(HttpServerErrorException.ServiceUnavailable.class,
                        () -> CanonCcapiNativeSequenceTrigger.trigger(backend));

                var operation = assertInstanceOf(CameraOperationException.class, failure.getCause());
                assertEquals(CameraOperationException.Reason.DEVICE_BUSY, operation.reason());
                assertEquals(2, catalogReads.get());
                assertEquals(1, shutterPosts.get());
            }
        } finally {
            server.stop(0);
            meterRegistry.close();
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        try (exchange) {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}
