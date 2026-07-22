package com.photographer.focusstack.camera.backend;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.photographer.focusstack.app.config.CameraConfig;
import com.photographer.focusstack.app.config.CcapiAuthenticationProperties;
import com.photographer.focusstack.camera.exception.CameraAuthenticationException;
import com.photographer.focusstack.camera.exception.CameraOperationException;
import com.photographer.focusstack.common.model.CameraProfile;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.photographer.focusstack.camera.exception.CameraAuthenticationException.Reason.CREDENTIALS_REJECTED;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CcapiDigestEdgeCaseTest {

    private final List<DigestEdgeServer> servers = new ArrayList<>();
    private final List<CcapiTransport> transports = new ArrayList<>();
    private final List<SimpleMeterRegistry> registries = new ArrayList<>();

    @AfterEach
    void closeResources() {
        transports.forEach(CcapiTransport::close);
        servers.forEach(DigestEdgeServer::close);
        registries.forEach(SimpleMeterRegistry::close);
    }

    @Test
    void acceptsQuotedParametersWithAndWithoutOpaque() throws Exception {
        for (DigestEdgeServer.Mode mode : List.of(
                DigestEdgeServer.Mode.QUOTED_WITH_OPAQUE,
                DigestEdgeServer.Mode.QUOTED_WITHOUT_OPAQUE)) {
            DigestEdgeServer server = server(mode, InetAddress.getLoopbackAddress(),
                    "quoted-user", "quoted-password");
            CcapiTransport transport = transport(
                    "quoted-" + mode.name().toLowerCase(Locale.ROOT),
                    server,
                    "quoted-user",
                    "quoted-password",
                    Duration.ofSeconds(2));

            @SuppressWarnings("unchecked")
            Map<String, Object> response = transport.restTemplate().getForObject(
                    server.baseUri() + "/ccapi", Map.class);

            assertThat(response).containsEntry("status", "ok");
            assertThat(server.requestCount()).isEqualTo(2);
            assertThat(server.nonEmptyAuthorizationHeaders()).hasSize(1);
            if (mode == DigestEdgeServer.Mode.QUOTED_WITHOUT_OPAQUE) {
                assertThat(server.nonEmptyAuthorizationHeaders().getFirst())
                        .doesNotContainIgnoringCase("opaque=");
            }
        }
    }

    @Test
    void basicOnlyChallengeNeverTriggersBasicAuthorization() throws Exception {
        DigestEdgeServer server = server(DigestEdgeServer.Mode.BASIC_ONLY,
                InetAddress.getLoopbackAddress(), "basic-user", "basic-password");
        CcapiTransport transport = transport(
                "basic-profile", server, "basic-user", "basic-password",
                Duration.ofSeconds(2));

        assertThatThrownBy(() -> transport.restTemplate().getForObject(
                server.baseUri() + "/ccapi", Map.class))
                .isInstanceOfSatisfying(CameraAuthenticationException.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(CREDENTIALS_REJECTED);
                    assertSanitized(failure, server);
                });

        assertThat(server.requestCount()).isOne();
        assertThat(server.authorizationHeaders()).containsExactly("");
    }

    @Test
    void unsupportedAlgorithmAndQopAreRejectedBeforeCredentialReplay() throws Exception {
        for (DigestEdgeServer.Mode mode : List.of(
                DigestEdgeServer.Mode.UNSUPPORTED_ALGORITHM,
                DigestEdgeServer.Mode.AUTH_INT_ONLY)) {
            DigestEdgeServer server = server(mode, InetAddress.getLoopbackAddress(),
                    "unsupported-user", "unsupported-password");
            CcapiTransport transport = transport(
                    "unsupported-" + mode.name().toLowerCase(Locale.ROOT),
                    server,
                    "unsupported-user",
                    "unsupported-password",
                    Duration.ofSeconds(2));

            assertThatThrownBy(() -> transport.restTemplate().getForObject(
                    server.baseUri() + "/ccapi", Map.class))
                    .isInstanceOfSatisfying(CameraAuthenticationException.class, failure -> {
                        assertThat(failure.reason()).isEqualTo(CREDENTIALS_REJECTED);
                        assertSanitized(failure, server);
                    });

            assertThat(server.requestCount()).isOne();
            assertThat(server.authorizationHeaders()).containsExactly("");
        }
    }

    @Test
    void staleNonceRefreshIsTransparentAndBounded() throws Exception {
        DigestEdgeServer server = server(DigestEdgeServer.Mode.STALE_ONCE,
                InetAddress.getLoopbackAddress(), "stale-user", "stale-password");
        CcapiTransport transport = transport(
                "stale-profile", server, "stale-user", "stale-password",
                Duration.ofSeconds(2));

        @SuppressWarnings("unchecked")
        Map<String, Object> response = transport.restTemplate().getForObject(
                server.baseUri() + "/ccapi", Map.class);

        assertThat(response).containsEntry("status", "ok");
        assertThat(server.requestCount()).isEqualTo(3);
        assertThat(server.nonEmptyAuthorizationHeaders()).hasSize(2);
        assertThat(server.authorizationParameters())
                .extracting(parameters -> parameters.get("nonce"))
                .containsExactly(DigestEdgeServer.NONCE_ONE, DigestEdgeServer.NONCE_TWO);
        assertThat(server.staleChallengeSent()).isTrue();
    }

    @Test
    void nonceCountProgressesForRepeatedRequestsOnOneTransport() throws Exception {
        DigestEdgeServer server = server(DigestEdgeServer.Mode.QUOTED_WITH_OPAQUE,
                InetAddress.getLoopbackAddress(), "counter-user", "counter-password");
        CcapiTransport transport = transport(
                "counter-profile", server, "counter-user", "counter-password",
                Duration.ofSeconds(2));
        RestTemplate client = transport.restTemplate();

        for (int request = 0; request < 3; request++) {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = client.getForObject(
                    server.baseUri() + "/ccapi?request=" + request, Map.class);
            assertThat(response).containsEntry("status", "ok");
        }

        List<Long> nonceCounts = server.authorizationParameters().stream()
                .map(parameters -> parameters.get("nc"))
                .filter(value -> value != null && !value.isBlank())
                .map(value -> Long.parseLong(value, 16))
                .toList();
        assertThat(nonceCounts).hasSize(3);
        assertThat(nonceCounts).isStrictlyIncreasing();
        assertThat(server.requestCount()).isBetween(4, 6);
    }

    @Test
    void parallelProfilesNeverCrossCredentialsNonceOrOpaque() throws Exception {
        DigestEdgeServer first = server(DigestEdgeServer.Mode.QUOTED_WITH_OPAQUE,
                InetAddress.getLoopbackAddress(), "alpha-user", "alpha-password");
        DigestEdgeServer second = server(DigestEdgeServer.Mode.QUOTED_WITH_OPAQUE,
                InetAddress.getLoopbackAddress(), "beta-user", "beta-password");
        CcapiTransport firstTransport = transport(
                "alpha-profile", first, "alpha-user", "alpha-password",
                Duration.ofSeconds(2));
        CcapiTransport secondTransport = transport(
                "beta-profile", second, "beta-user", "beta-password",
                Duration.ofSeconds(2));

        CompletableFuture<Map> alpha = CompletableFuture.supplyAsync(() ->
                firstTransport.restTemplate().getForObject(first.baseUri() + "/ccapi", Map.class));
        CompletableFuture<Map> beta = CompletableFuture.supplyAsync(() ->
                secondTransport.restTemplate().getForObject(second.baseUri() + "/ccapi", Map.class));

        assertThat(alpha.get(5, TimeUnit.SECONDS)).containsEntry("status", "ok");
        assertThat(beta.get(5, TimeUnit.SECONDS)).containsEntry("status", "ok");
        assertThat(first.authorizationParameters())
                .allSatisfy(parameters -> {
                    assertThat(parameters).containsEntry("username", "alpha-user");
                    assertThat(parameters).containsEntry("nonce", first.nonceOne());
                    assertThat(parameters).containsEntry("opaque", first.opaque());
                    assertThat(parameters.values()).doesNotContain("beta-user", second.nonceOne(), second.opaque());
                });
        assertThat(second.authorizationParameters())
                .allSatisfy(parameters -> {
                    assertThat(parameters).containsEntry("username", "beta-user");
                    assertThat(parameters).containsEntry("nonce", second.nonceOne());
                    assertThat(parameters).containsEntry("opaque", second.opaque());
                    assertThat(parameters.values()).doesNotContain("alpha-user", first.nonceOne(), first.opaque());
                });
    }

    @Test
    void bracketedIpv6OriginUsesTheExactIpv6HostAndPortScope() throws Exception {
        InetAddress ipv6 = InetAddress.getByName("::1");
        Assumptions.assumeTrue(ipv6 instanceof Inet6Address && ipv6.isReachable(500),
                "IPv6 loopback is unavailable on this runner");
        DigestEdgeServer server = server(DigestEdgeServer.Mode.QUOTED_WITH_OPAQUE,
                ipv6, "ipv6-user", "ipv6-password");
        CcapiTransport transport = transport(
                "ipv6-profile", server, "ipv6-user", "ipv6-password",
                Duration.ofSeconds(2));

        @SuppressWarnings("unchecked")
        Map<String, Object> response = transport.restTemplate().getForObject(
                server.baseUri() + "/ccapi", Map.class);

        assertThat(response).containsEntry("status", "ok");
        assertThat(CcapiRestTemplateFactory.normalizeBaseUri(server.baseUri()).toString())
                .startsWith("http://[")
                .endsWith(":" + server.port());
        assertThat(server.authorizationParameters())
                .allSatisfy(parameters -> assertThat(parameters)
                        .containsEntry("username", "ipv6-user"));
    }

    @Test
    void responseTimeoutRemainsDistinctFromAuthenticationRejection() throws Exception {
        DigestEdgeServer server = server(DigestEdgeServer.Mode.SLOW_SUCCESS,
                InetAddress.getLoopbackAddress(), "timeout-user", "timeout-password");
        CcapiTransport transport = transport(
                "timeout-profile", server, "timeout-user", "timeout-password",
                Duration.ofMillis(80));
        long started = System.nanoTime();

        assertThatThrownBy(() -> transport.restTemplate().getForObject(
                server.baseUri() + "/ccapi", Map.class))
                .isInstanceOfSatisfying(CameraOperationException.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(TIMEOUT);
                    assertThat(failure).isNotInstanceOf(CameraAuthenticationException.class);
                    assertSanitized(failure, server);
                });

        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isLessThan(Duration.ofSeconds(2));
        assertThat(server.requestCount()).isOne();
    }

    @Test
    void negativeAuthenticationLogsDoNotContainSecretsOrChallengeMaterial() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            DigestEdgeServer server = server(DigestEdgeServer.Mode.QUOTED_WITH_OPAQUE,
                    InetAddress.getLoopbackAddress(), "log-secret-user", "server-password");
            CcapiTransport transport = transport(
                    "log-profile", server, "log-secret-user", "wrong-secret-password",
                    Duration.ofSeconds(2));

            assertThatThrownBy(() -> transport.restTemplate().getForObject(
                    server.baseUri() + "/ccapi", Map.class))
                    .isInstanceOf(CameraAuthenticationException.class);

            String logs = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertThat(logs)
                    .doesNotContain("log-secret-user")
                    .doesNotContain("wrong-secret-password")
                    .doesNotContain(server.realm())
                    .doesNotContain(server.nonceOne())
                    .doesNotContain(server.opaque())
                    .doesNotContainIgnoringCase("Authorization: Digest");
            assertThat(server.requestCount()).isBetween(2, 3);
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }
    }

    private DigestEdgeServer server(
            DigestEdgeServer.Mode mode,
            InetAddress address,
            String username,
            String password) throws IOException {
        DigestEdgeServer server = new DigestEdgeServer(mode, address, username, password);
        servers.add(server);
        return server;
    }

    private CcapiTransport transport(
            String profileId,
            DigestEdgeServer server,
            String username,
            String password,
            Duration readTimeout) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registries.add(registry);
        CameraConfig config = new CameraConfig(
                URI.create("http://127.0.0.1:8080"),
                false,
                Duration.ofMillis(500));
        CcapiRestTemplateFactory factory = new CcapiRestTemplateFactory(
                config,
                credentials(profileId, username, password),
                new CcapiOutcomeRecorder(registry),
                readTimeout);
        CcapiTransport transport = factory.create(profile(profileId), server.baseUri());
        transports.add(transport);
        return transport;
    }

    private static CameraProfile profile(String id) {
        return new CameraProfile(id, "Synthetic camera", "canon-ccapi",
                "localhost", 1, 20, 0.1);
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

    private static void assertSanitized(Throwable failure, DigestEdgeServer server) {
        assertThat(failure.getMessage())
                .doesNotContain(server.username())
                .doesNotContain(server.password())
                .doesNotContain(server.realm())
                .doesNotContain(server.nonceOne())
                .doesNotContain(server.opaque());
    }

    private static final class DigestEdgeServer implements AutoCloseable {
        private enum Mode {
            QUOTED_WITH_OPAQUE,
            QUOTED_WITHOUT_OPAQUE,
            STALE_ONCE,
            BASIC_ONLY,
            UNSUPPORTED_ALGORITHM,
            AUTH_INT_ONLY,
            SLOW_SUCCESS
        }

        private static final Pattern PARAMETER = Pattern.compile(
                "([a-zA-Z0-9_]+)=(?:\\\"([^\\\"]*)\\\"|([^,\\s]+))");
        private static final String NONCE_ONE = "edge-nonce-one";
        private static final String NONCE_TWO = "edge-nonce-two";

        private final Mode mode;
        private final String username;
        private final String password;
        private final String realm;
        private final String opaque;
        private final HttpServer server;
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicBoolean staleSent = new AtomicBoolean();
        private final List<String> authorizationHeaders =
                Collections.synchronizedList(new ArrayList<>());

        private DigestEdgeServer(
                Mode mode,
                InetAddress address,
                String username,
                String password) throws IOException {
            this.mode = mode;
            this.username = username;
            this.password = password;
            this.realm = "edge-realm-" + mode.name().toLowerCase(Locale.ROOT);
            this.opaque = "edge-opaque-" + mode.name().toLowerCase(Locale.ROOT);
            this.server = HttpServer.create(new InetSocketAddress(address, 0), 0);
            this.server.createContext("/", this::handle);
            this.server.start();
        }

        private URI baseUri() {
            String host = server.getAddress().getAddress() instanceof Inet6Address
                    ? "[" + server.getAddress().getAddress().getHostAddress() + "]"
                    : server.getAddress().getAddress().getHostAddress();
            return URI.create("http://" + host + ":" + port());
        }

        private int port() {
            return server.getAddress().getPort();
        }

        private int requestCount() {
            return requests.get();
        }

        private String username() {
            return username;
        }

        private String password() {
            return password;
        }

        private String realm() {
            return realm;
        }

        private String opaque() {
            return opaque;
        }

        private String nonceOne() {
            return NONCE_ONE;
        }

        private boolean staleChallengeSent() {
            return staleSent.get();
        }

        private List<String> authorizationHeaders() {
            return List.copyOf(authorizationHeaders);
        }

        private List<String> nonEmptyAuthorizationHeaders() {
            return authorizationHeaders().stream().filter(value -> !value.isEmpty()).toList();
        }

        private List<Map<String, String>> authorizationParameters() {
            return nonEmptyAuthorizationHeaders().stream()
                    .map(header -> parseParameters(header.substring("Digest ".length())))
                    .toList();
        }

        private void handle(HttpExchange exchange) throws IOException {
            requests.incrementAndGet();
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            authorizationHeaders.add(authorization == null ? "" : authorization);

            if (mode == Mode.SLOW_SUCCESS) {
                try {
                    Thread.sleep(600);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                sendSuccess(exchange);
                return;
            }
            if (mode == Mode.BASIC_ONLY) {
                challenge(exchange, "Basic realm=\"" + realm + "\"");
                return;
            }
            if (mode == Mode.UNSUPPORTED_ALGORITHM) {
                challenge(exchange, digestChallenge(NONCE_ONE, "SHA-256", "auth", false, true));
                return;
            }
            if (mode == Mode.AUTH_INT_ONLY) {
                challenge(exchange, digestChallenge(NONCE_ONE, "MD5", "auth-int", false, true));
                return;
            }

            String expectedNonce = mode == Mode.STALE_ONCE && staleSent.get()
                    ? NONCE_TWO : NONCE_ONE;
            if (authorization == null || !validAuthorization(exchange, authorization, expectedNonce)) {
                challenge(exchange, digestChallenge(
                        expectedNonce,
                        "MD5",
                        "auth",
                        false,
                        mode != Mode.QUOTED_WITHOUT_OPAQUE));
                return;
            }
            if (mode == Mode.STALE_ONCE && staleSent.compareAndSet(false, true)) {
                challenge(exchange, digestChallenge(NONCE_TWO, "MD5", "auth", true, true));
                return;
            }
            sendSuccess(exchange);
        }

        private String digestChallenge(
                String nonce,
                String algorithm,
                String qop,
                boolean stale,
                boolean includeOpaque) {
            StringBuilder value = new StringBuilder("Digest   realm=\"")
                    .append(realm)
                    .append("\", nonce=\"")
                    .append(nonce)
                    .append("\", algorithm=\"")
                    .append(algorithm)
                    .append("\", qop=\"")
                    .append(qop)
                    .append('"');
            if (includeOpaque) {
                value.append(", opaque=\"").append(opaque).append('"');
            }
            if (stale) {
                value.append(", stale=true");
            }
            return value.toString();
        }

        private boolean validAuthorization(
                HttpExchange exchange,
                String authorization,
                String expectedNonce) {
            if (!authorization.startsWith("Digest ")) {
                return false;
            }
            Map<String, String> parameters = parseParameters(
                    authorization.substring("Digest ".length()));
            String uri = parameters.get("uri");
            String nc = parameters.get("nc");
            String cnonce = parameters.get("cnonce");
            String qop = parameters.get("qop");
            String suppliedResponse = parameters.get("response");
            boolean opaqueValid = mode == Mode.QUOTED_WITHOUT_OPAQUE
                    ? parameters.get("opaque") == null
                    : opaque.equals(parameters.get("opaque"));
            if (!username.equals(parameters.get("username"))
                    || !expectedNonce.equals(parameters.get("nonce"))
                    || !opaqueValid
                    || uri == null || nc == null || cnonce == null
                    || !"auth".equals(qop) || suppliedResponse == null) {
                return false;
            }
            String ha1 = md5(username + ":" + realm + ":" + password);
            String ha2 = md5(exchange.getRequestMethod() + ":" + uri);
            String expected = md5(ha1 + ":" + expectedNonce + ":" + nc + ":"
                    + cnonce + ":" + qop + ":" + ha2);
            return expected.equalsIgnoreCase(suppliedResponse);
        }

        private static Map<String, String> parseParameters(String value) {
            Map<String, String> result = new LinkedHashMap<>();
            Matcher matcher = PARAMETER.matcher(value);
            while (matcher.find()) {
                result.put(matcher.group(1).toLowerCase(Locale.ROOT),
                        matcher.group(2) != null ? matcher.group(2) : matcher.group(3));
            }
            return result;
        }

        private void challenge(HttpExchange exchange, String value) throws IOException {
            exchange.getResponseHeaders().set("WWW-Authenticate", value);
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        }

        private static void sendSuccess(HttpExchange exchange) throws IOException {
            byte[] response = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try {
                exchange.getResponseBody().write(response);
            } catch (IOException ignored) {
                // A deliberately timed-out client may already have closed the socket.
            } finally {
                exchange.close();
            }
        }

        private static String md5(String value) {
            try {
                byte[] digest = MessageDigest.getInstance("MD5")
                        .digest(value.getBytes(StandardCharsets.ISO_8859_1));
                StringBuilder hexadecimal = new StringBuilder(digest.length * 2);
                for (byte current : digest) {
                    hexadecimal.append(String.format(Locale.ROOT, "%02x", current & 0xff));
                }
                return hexadecimal.toString();
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("MD5 is required by the Java platform", impossible);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
