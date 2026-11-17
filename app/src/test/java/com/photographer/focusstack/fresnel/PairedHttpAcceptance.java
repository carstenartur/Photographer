package com.photographer.focusstack.fresnel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.photographer.focusstack.app.security.UserEntity;
import com.photographer.focusstack.app.security.UserRepository;
import com.photographer.focusstack.app.security.UserRole;
import com.photographer.focusstack.common.model.CameraProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "focusstack.fresnel.enabled=true", "camera.mock.enabled=true",
        "focusstack.security.admin-password=TestAdminPassword!42"
})
@DirtiesContext
abstract class PairedHttpAcceptance {
    private static final String ROOT = "/api/v1/integrations/fresnel";
    private static final String ADMIN = "/api/v1/admin/integrations/fresnel";
    private static final String STATUS = "/api/v1/fresnel/status";
    private static final String ADMIN_AUTH = basic("admin", "TestAdminPassword!42");
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir static Path directory;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry properties) {
        properties.add("focusstack.storage.images-dir", () -> directory.resolve("images").toString());
        properties.add("focusstack.fresnel.directory", () -> directory.resolve("fresnel").toString());
    }

    @Value("${local.server.port}") int port;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;

    @Test
    void pairsOnceAndExposesScopedCameraDiscoveryUntilAdminRevokesAccess() throws Exception {
        String origin = "http://127.0.0.1:" + port;
        users.save(new UserEntity("fresnel-observer", passwords.encode("Observer-Test-42"), UserRole.USER));
        var grantRequest = Map.of("baseUrl", origin, "scopes",
                List.of("fresnel:descriptor:read", "fresnel:cameras:read"));
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            JsonNode fresh = json(send(http, "GET", ADMIN + "/pairings", ADMIN_AUTH, null), 200);
            assertTrue(fresh.path("enabled").asBoolean());
            assertEquals(0, fresh.path("pairings").size());
            assertEquals("NOT_CONFIGURED", state(http));
            assertEquals(403, send(http, "POST", ADMIN + "/pairing-codes",
                    basic("fresnel-observer", "Observer-Test-42"), grantRequest).statusCode());
            var missingBearer = send(http, "GET", ROOT + "/descriptor", ADMIN_AUTH, null);
            assertEquals("AUTHENTICATION_FAILED", json(missingBearer, 401).path("code").asText());
            assertEquals("Bearer", missingBearer.headers().firstValue("WWW-Authenticate").orElseThrow());

            var camera = new CameraProfile("internal-test-id", "Private local camera", "mock",
                    "internal-camera.invalid", 5, 20, 0.15);
            assertEquals(201, send(http, "POST", "/api/v1/cameras", ADMIN_AUTH, camera).statusCode());
            var grant = send(http, "POST", ADMIN + "/pairing-codes", ADMIN_AUTH, grantRequest);
            String code = json(grant, 201).required("pairingCode").asText();
            assertTrue(grant.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
            var pairing = Map.of("pairingCode", code, "fresnelInstanceId", "test-fresnel",
                    "fresnelDisplayName", "Test lab", "fresnelApplicationVersion", "1.0",
                    "fresnelProtocolVersion", 1);
            JsonNode exchanged = json(send(http, "POST", ROOT + "/pair", null, pairing), 201);
            String id = exchanged.required("pairingId").asText();
            String token = exchanged.required("bearerToken").asText();
            String serviceAuth = "Bearer " + token;
            assertEquals("PAIRING_CODE_INVALID",
                    json(send(http, "POST", ROOT + "/pair", null, pairing), 403).path("code").asText());
            assertEquals("INSUFFICIENT_SCOPE", json(send(http, "GET",
                    ROOT + "/capture-sessions/session", serviceAuth, null), 403).required("code").asText());
            assertEquals("PAIRING", state(http));

            JsonNode descriptor = json(send(http, "GET", ROOT + "/descriptor", serviceAuth, null), 200);
            assertEquals(1, descriptor.required("protocolVersion").asInt());
            assertEquals(exchanged.path("descriptor").required("instanceId"), descriptor.required("instanceId"));
            var discovery = send(http, "GET", ROOT + "/cameras", serviceAuth, null);
            JsonNode cameras = json(discovery, 200);
            assertTrue(cameras.isArray() && !cameras.isEmpty());
            assertFalse(cameras.get(0).required("cameraProfileId").asText().isBlank());
            assertFalse(discovery.body().contains(camera.host()));
            assertEquals("CONNECTED", json(send(http, "GET", ROOT + "/health", serviceAuth, null), 200)
                    .required("state").asText());
            assertEquals("CONNECTED", state(http));

            var listing = send(http, "GET", ADMIN + "/pairings", ADMIN_AUTH, null);
            JsonNode summary = json(listing, 200).required("pairings").get(0).required("pairing");
            assertEquals(id, summary.required("pairingId").asText());
            assertEquals("CONNECTED", summary.required("state").asText());
            assertFalse(listing.body().contains(token));
            assertFalse(listing.body().contains(code));
            assertFalse(summary.has("hash"));

            assertEquals(204, send(http, "DELETE", ADMIN + "/pairings/" + id, ADMIN_AUTH, null).statusCode());
            assertEquals("REVOKED", state(http));
            var revoked = send(http, "GET", ROOT + "/cameras", serviceAuth, null);
            assertEquals("AUTHENTICATION_FAILED", json(revoked, 401).required("code").asText());
            assertEquals("Bearer", revoked.headers().firstValue("WWW-Authenticate").orElseThrow());
            FresnelCaptureHttpChecks.verify(origin, http, ADMIN_AUTH);
        }
    }

    private String state(HttpClient http) throws Exception {
        return json(send(http, "GET", STATUS, ADMIN_AUTH, null), 200).required("state").asText();
    }

    private HttpResponse<String> send(HttpClient http, String method, String path, String auth, Object body)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Accept", "application/json");
        if (auth != null) builder.header("Authorization", auth);
        if (path.equals(ROOT + "/pair")) builder.header("Idempotency-Key", "pair-test");
        var data = HttpRequest.BodyPublishers.noBody();
        if (body != null) {
            builder.header("Content-Type", "application/json");
            data = HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body));
        }
        return http.send(builder.method(method, data).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> response, int expected) throws Exception {
        assertEquals(expected, response.statusCode(), () -> response.request().uri().getPath());
        return JSON.readTree(response.body());
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password)
                .getBytes(StandardCharsets.UTF_8));
    }
}
