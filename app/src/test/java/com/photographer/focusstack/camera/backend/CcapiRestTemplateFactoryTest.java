package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.app.config.CameraConfig;
import com.photographer.focusstack.app.config.CcapiAuthenticationProperties;
import com.photographer.focusstack.camera.exception.CameraAuthenticationException;
import com.photographer.focusstack.common.model.CameraProfile;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.photographer.focusstack.camera.exception.CameraAuthenticationException.Reason.CREDENTIALS_REJECTED;
import static com.photographer.focusstack.camera.exception.CameraAuthenticationException.Reason.CREDENTIALS_REQUIRED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CcapiRestTemplateFactoryTest {

    private DigestCameraServer server;
    private CcapiTransport transport;

    @AfterEach
    void stopResources() {
        if (transport != null) {
            transport.close();
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    void answersCanonCompatibleDigestChallengeWithoutPreemptiveAuthorization() throws Exception {
        server = new DigestCameraServer("camera-user", "camera-password");
        transport = factory(credentials("camera-profile", "camera-user", "camera-password"))
                .create(profile("camera-profile"), server.baseUri());
        RestTemplate client = transport.restTemplate();

        @SuppressWarnings("unchecked")
        Map<String, Object> response = client.getForObject(
                server.baseUri() + "/ccapi", Map.class);

        assertNotNull(response);
        assertEquals("ok", response.get("status"));
        assertEquals(2, server.requestCount());
        assertTrue(server.authorizationHeaders().getFirst().isEmpty());
        assertTrue(server.authorizationHeaders().get(1).startsWith("Digest "));
    }

    @Test
    void replaysTheExactJsonBodyAfterTheDigestChallenge() throws Exception {
        server = new DigestCameraServer("camera-user", "camera-password");
        transport = factory(credentials("camera-profile", "camera-user", "camera-password"))
                .create(profile("camera-profile"), server.baseUri());
        RestTemplate client = transport.restTemplate();
        byte[] body = "{\"value\":20}".getBytes(StandardCharsets.UTF_8);

        client.exchange(
                server.baseUri() + "/setting",
                HttpMethod.PUT,
                new HttpEntity<>(body, CcapiHttpHeaders.json()),
                String.class);

        assertEquals(2, server.requestCount());
        assertEquals(2, server.requestBodies().size());
        assertEquals(new String(body, StandardCharsets.UTF_8), server.requestBodies().getFirst());
        assertEquals(new String(body, StandardCharsets.UTF_8), server.requestBodies().get(1));
    }

    @Test
    void wrongCredentialsProduceABoundedSanitizedFailure() throws Exception {
        server = new DigestCameraServer("camera-user", "correct-password");
        transport = factory(credentials("camera-profile", "camera-user", "wrong-password"))
                .create(profile("camera-profile"), server.baseUri());
        RestTemplate client = transport.restTemplate();

        CameraAuthenticationException failure = assertThrows(
                CameraAuthenticationException.class,
                () -> client.getForObject(server.baseUri() + "/ccapi", Map.class));

        assertEquals(CREDENTIALS_REJECTED, failure.reason());
        assertTrue(server.requestCount() >= 2);
        assertTrue(server.requestCount() <= 3);
        assertFalse(failure.getMessage().contains("camera-user"));
        assertFalse(failure.getMessage().contains("wrong-password"));
        assertFalse(failure.getMessage().contains(DigestCameraServer.NONCE));
        assertFalse(failure.getMessage().contains(DigestCameraServer.OPAQUE));
    }

    @Test
    void missingCredentialsAreDistinctFromRejectedCredentials() throws Exception {
        server = new DigestCameraServer("camera-user", "camera-password");
        transport = factory(new CcapiAuthenticationProperties())
                .create(profile("unconfigured-profile"), server.baseUri());
        RestTemplate client = transport.restTemplate();

        CameraAuthenticationException failure = assertThrows(
                CameraAuthenticationException.class,
                () -> client.getForObject(server.baseUri() + "/ccapi", Map.class));

        assertEquals(CREDENTIALS_REQUIRED, failure.reason());
        assertEquals(1, server.requestCount());
        assertTrue(server.authorizationHeaders().getFirst().isEmpty());
    }

    @Test
    void credentialsAreNeverResolvedByHostOrDisplayName() throws Exception {
        server = new DigestCameraServer("camera-user", "camera-password");
        CcapiAuthenticationProperties properties = credentials(
                server.baseUri().getHost(), "camera-user", "camera-password");
        transport = factory(properties)
                .create(profile("different-profile"), server.baseUri());
        RestTemplate client = transport.restTemplate();

        CameraAuthenticationException failure = assertThrows(
                CameraAuthenticationException.class,
                () -> client.getForObject(server.baseUri() + "/ccapi", Map.class));

        assertEquals(CREDENTIALS_REQUIRED, failure.reason());
    }

    @Test
    void closeIsIdempotentAndClearsTheTransportOwnedPasswordCopy() throws Exception {
        server = new DigestCameraServer("camera-user", "camera-password");
        transport = factory(credentials("camera-profile", "camera-user", "camera-password"))
                .create(profile("camera-profile"), server.baseUri());

        assertFalse(transport.passwordCleared());
        transport.close();
        transport.close();

        assertTrue(transport.isClosed());
        assertTrue(transport.passwordCleared());
        assertThrows(IllegalStateException.class, transport::restTemplate);
    }

    private static CcapiRestTemplateFactory factory(
            CcapiAuthenticationProperties properties) {
        CameraConfig config = new CameraConfig(
                URI.create("http://127.0.0.1:8080"),
                false,
                Duration.ofSeconds(2));
        return new CcapiRestTemplateFactory(config, properties);
    }

    private static CameraProfile profile(String id) {
        return new CameraProfile(id, "Test camera", "canon-ccapi",
                "127.0.0.1", 1, 20, 0.1);
    }

    private static CcapiAuthenticationProperties credentials(
            String profileId,
            String username,
            String password) {
        CcapiAuthenticationProperties.Credential credential =
                new CcapiAuthenticationProperties.Credential();
        credential.setUsername(username);
        credential.setPassword(password);
        CcapiAuthenticationProperties properties = new CcapiAuthenticationProperties();
        properties.setCredentials(Map.of(profileId, credential));
        return properties;
    }

    private static final class CcapiHttpHeaders {
        private static org.springframework.http.HttpHeaders json() {
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            return headers;
        }
    }

    private static final class DigestCameraServer implements AutoCloseable {
        private static final String REALM = "CameraControlApi";
        private static final String NONCE = "deterministic-camera-nonce";
        private static final String OPAQUE = "deterministic-camera-opaque";
        private static final Pattern PARAMETER = Pattern.compile(
                "([a-zA-Z0-9_]+)=(?:\\\"([^\\\"]*)\\\"|([^,\\s]+))");

        private final String username;
        private final String password;
        private final HttpServer httpServer;
        private final AtomicInteger requestCount = new AtomicInteger();
        private final List<String> authorizationHeaders =
                java.util.Collections.synchronizedList(new ArrayList<>());
        private final List<String> requestBodies =
                java.util.Collections.synchronizedList(new ArrayList<>());

        private DigestCameraServer(String username, String password) throws IOException {
            this.username = username;
            this.password = password;
            this.httpServer = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            this.httpServer.createContext("/", this::handle);
            this.httpServer.start();
        }

        private URI baseUri() {
            return URI.create("http://127.0.0.1:" + httpServer.getAddress().getPort());
        }

        private int requestCount() {
            return requestCount.get();
        }

        private List<String> authorizationHeaders() {
            return List.copyOf(authorizationHeaders);
        }

        private List<String> requestBodies() {
            return List.copyOf(requestBodies);
        }

        private void handle(HttpExchange exchange) throws IOException {
            requestCount.incrementAndGet();
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            authorizationHeaders.add(authorization == null ? "" : authorization);
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            if (authorization == null || !validAuthorization(exchange, authorization)) {
                exchange.getResponseHeaders().set(
                        "WWW-Authenticate",
                        "Digest realm=\"" + REALM + "\", nonce=\"" + NONCE
                                + "\", opaque=\"" + OPAQUE
                                + "\", algorithm=MD5, qop=\"auth\"");
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }

            byte[] response = (exchange.getRequestURI().getPath().equals("/ccapi")
                    ? "{\"status\":\"ok\"}"
                    : "{}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        }

        private boolean validAuthorization(HttpExchange exchange, String authorization) {
            if (!authorization.startsWith("Digest ")) {
                return false;
            }
            Map<String, String> parameters = parseParameters(
                    authorization.substring("Digest ".length()));
            String suppliedUsername = parameters.get("username");
            String uri = parameters.get("uri");
            String nc = parameters.get("nc");
            String cnonce = parameters.get("cnonce");
            String qop = parameters.get("qop");
            String suppliedResponse = parameters.get("response");
            if (!username.equals(suppliedUsername)
                    || !NONCE.equals(parameters.get("nonce"))
                    || !OPAQUE.equals(parameters.get("opaque"))
                    || uri == null || nc == null || cnonce == null
                    || !"auth".equals(qop) || suppliedResponse == null) {
                return false;
            }
            String ha1 = md5(username + ":" + REALM + ":" + password);
            String ha2 = md5(exchange.getRequestMethod() + ":" + uri);
            String expected = md5(ha1 + ":" + NONCE + ":" + nc + ":"
                    + cnonce + ":" + qop + ":" + ha2);
            return expected.equalsIgnoreCase(suppliedResponse);
        }

        private static Map<String, String> parseParameters(String value) {
            Map<String, String> result = new LinkedHashMap<>();
            Matcher matcher = PARAMETER.matcher(value);
            while (matcher.find()) {
                result.put(matcher.group(1).toLowerCase(java.util.Locale.ROOT),
                        matcher.group(2) != null ? matcher.group(2) : matcher.group(3));
            }
            return result;
        }

        private static String md5(String value) {
            try {
                byte[] digest = MessageDigest.getInstance("MD5")
                        .digest(value.getBytes(StandardCharsets.ISO_8859_1));
                StringBuilder hexadecimal = new StringBuilder(digest.length * 2);
                for (byte current : digest) {
                    hexadecimal.append(String.format(java.util.Locale.ROOT, "%02x", current & 0xff));
                }
                return hexadecimal.toString();
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("MD5 is required by the Java platform", exception);
            }
        }

        @Override
        public void close() {
            httpServer.stop(0);
        }
    }
}
