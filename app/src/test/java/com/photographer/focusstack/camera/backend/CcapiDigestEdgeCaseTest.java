package com.photographer.focusstack.camera.backend;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.photographer.focusstack.app.config.CameraConfig;
import com.photographer.focusstack.app.config.CcapiAuthenticationProperties;
import com.photographer.focusstack.camera.exception.CameraAuthenticationException;
import com.photographer.focusstack.camera.exception.CameraOperationException;
import com.photographer.focusstack.common.model.CameraProfile;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.photographer.focusstack.camera.exception.CameraAuthenticationException.Reason.CREDENTIALS_REJECTED;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CcapiDigestEdgeCaseTest {

    private final List<CcapiDigestTestServer> servers = new ArrayList<>();
    private final List<CcapiTransport> transports = new ArrayList<>();
    private final List<SimpleMeterRegistry> registries = new ArrayList<>();

    @AfterEach
    void closeResources() {
        transports.forEach(CcapiTransport::close);
        servers.forEach(CcapiDigestTestServer::close);
        registries.forEach(SimpleMeterRegistry::close);
    }

    @Test
    void acceptsQuotedParametersWithAndWithoutOpaque() throws Exception {
        for (CcapiDigestTestServer.Mode mode : List.of(
                CcapiDigestTestServer.Mode.QUOTED_WITH_OPAQUE,
                CcapiDigestTestServer.Mode.QUOTED_WITHOUT_OPAQUE)) {
            CcapiDigestTestServer server = server(mode, InetAddress.getLoopbackAddress(),
                    "quoted-user", "quoted-password");
            CcapiTransport transport = transport(
                    "quoted-" + mode.name().toLowerCase(Locale.ROOT),
                    server, "quoted-user", "quoted-password", Duration.ofSeconds(2));

            @SuppressWarnings("unchecked")
            Map<String, Object> response = transport.restTemplate().getForObject(
                    server.baseUri() + "/ccapi", Map.class);

            assertThat(response).containsEntry("status", "ok");
            assertThat(server.requestCount()).isEqualTo(2);
            assertThat(server.nonEmptyAuthorizationHeaders()).hasSize(1);
            if (mode == CcapiDigestTestServer.Mode.QUOTED_WITHOUT_OPAQUE) {
                assertThat(server.nonEmptyAuthorizationHeaders().getFirst())
                        .doesNotContainIgnoringCase("opaque=");
            }
        }
    }

    @Test
    void basicOnlyChallengeNeverTriggersBasicAuthorization() throws Exception {
        CcapiDigestTestServer server = server(CcapiDigestTestServer.Mode.BASIC_ONLY,
                InetAddress.getLoopbackAddress(), "basic-user", "basic-password");
        CcapiTransport transport = transport(
                "basic-profile", server, "basic-user", "basic-password",
                Duration.ofSeconds(2));

        assertThatThrownBy(() -> get(transport, server))
                .isInstanceOfSatisfying(CameraAuthenticationException.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(CREDENTIALS_REJECTED);
                    assertSanitized(failure, server);
                });
        assertThat(server.requestCount()).isOne();
        assertThat(server.authorizationHeaders()).containsExactly("");
    }

    @Test
    void unsupportedAlgorithmAndQopAreRejectedBeforeCredentialReplay() throws Exception {
        for (CcapiDigestTestServer.Mode mode : List.of(
                CcapiDigestTestServer.Mode.UNSUPPORTED_ALGORITHM,
                CcapiDigestTestServer.Mode.AUTH_INT_ONLY)) {
            CcapiDigestTestServer server = server(mode, InetAddress.getLoopbackAddress(),
                    "unsupported-user", "unsupported-password");
            CcapiTransport transport = transport(
                    "unsupported-" + mode.name().toLowerCase(Locale.ROOT),
                    server, "unsupported-user", "unsupported-password",
                    Duration.ofSeconds(2));

            assertThatThrownBy(() -> get(transport, server))
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
        CcapiDigestTestServer server = server(CcapiDigestTestServer.Mode.STALE_ONCE,
                InetAddress.getLoopbackAddress(), "stale-user", "stale-password");
        CcapiTransport transport = transport(
                "stale-profile", server, "stale-user", "stale-password",
                Duration.ofSeconds(2));

        assertThat(get(transport, server)).containsEntry("status", "ok");
        assertThat(server.requestCount()).isEqualTo(3);
        assertThat(server.authorizationParameters())
                .extracting(parameters -> parameters.get("nonce"))
                .containsExactly(
                        CcapiDigestTestServer.NONCE_ONE,
                        CcapiDigestTestServer.NONCE_TWO);
        assertThat(server.staleChallengeSent()).isTrue();
    }

    @Test
    void nonceCountProgressesForRepeatedRequestsOnOneTransport() throws Exception {
        CcapiDigestTestServer server = server(
                CcapiDigestTestServer.Mode.QUOTED_WITH_OPAQUE,
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
        for (int index = 1; index < nonceCounts.size(); index++) {
            assertThat(nonceCounts.get(index)).isGreaterThan(nonceCounts.get(index - 1));
        }
        assertThat(server.requestCount()).isBetween(4, 6);
    }

    @Test
    void parallelProfilesNeverCrossCredentialsNonceOrOpaque() throws Exception {
        CcapiDigestTestServer first = server(
                CcapiDigestTestServer.Mode.QUOTED_WITH_OPAQUE,
                InetAddress.getLoopbackAddress(), "alpha-user", "alpha-password");
        CcapiDigestTestServer second = server(
                CcapiDigestTestServer.Mode.QUOTED_WITH_OPAQUE,
                InetAddress.getLoopbackAddress(), "beta-user", "beta-password");
        CcapiTransport firstTransport = transport(
                "alpha-profile", first, "alpha-user", "alpha-password",
                Duration.ofSeconds(2));
        CcapiTransport secondTransport = transport(
                "beta-profile", second, "beta-user", "beta-password",
                Duration.ofSeconds(2));

        CompletableFuture<Map<String, Object>> alpha = CompletableFuture.supplyAsync(
                () -> get(firstTransport, first));
        CompletableFuture<Map<String, Object>> beta = CompletableFuture.supplyAsync(
                () -> get(secondTransport, second));

        assertThat(alpha.get(5, TimeUnit.SECONDS)).containsEntry("status", "ok");
        assertThat(beta.get(5, TimeUnit.SECONDS)).containsEntry("status", "ok");
        assertIsolated(first, "alpha-user", second);
        assertIsolated(second, "beta-user", first);
    }

    @Test
    void bracketedIpv6OriginUsesExactIpv6HostAndPortScope() throws Exception {
        InetAddress ipv6 = InetAddress.getByName("::1");
        Assumptions.assumeTrue(ipv6 instanceof Inet6Address && ipv6.isReachable(500),
                "IPv6 loopback is unavailable on this runner");
        CcapiDigestTestServer server = server(
                CcapiDigestTestServer.Mode.QUOTED_WITH_OPAQUE,
                ipv6, "ipv6-user", "ipv6-password");
        CcapiTransport transport = transport(
                "ipv6-profile", server, "ipv6-user", "ipv6-password",
                Duration.ofSeconds(2));

        assertThat(get(transport, server)).containsEntry("status", "ok");
        assertThat(CcapiRestTemplateFactory.normalizeBaseUri(server.baseUri()).toString())
                .startsWith("http://[")
                .endsWith(":" + server.port());
        assertThat(server.authorizationParameters())
                .allSatisfy(parameters -> assertThat(parameters)
                        .containsEntry("username", "ipv6-user"));
    }

    @Test
    void responseTimeoutRemainsDistinctFromAuthenticationRejection() throws Exception {
        CcapiDigestTestServer server = server(CcapiDigestTestServer.Mode.SLOW_SUCCESS,
                InetAddress.getLoopbackAddress(), "timeout-user", "timeout-password");
        CcapiTransport transport = transport(
                "timeout-profile", server, "timeout-user", "timeout-password",
                Duration.ofMillis(80));
        long started = System.nanoTime();

        assertThatThrownBy(() -> get(transport, server))
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
    void negativeAuthenticationLogsContainNoSecretsOrChallengeMaterial() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            CcapiDigestTestServer server = server(
                    CcapiDigestTestServer.Mode.QUOTED_WITH_OPAQUE,
                    InetAddress.getLoopbackAddress(), "log-secret-user", "server-password");
            CcapiTransport transport = transport(
                    "log-profile", server, "log-secret-user", "wrong-secret-password",
                    Duration.ofSeconds(2));

            assertThatThrownBy(() -> get(transport, server))
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> get(
            CcapiTransport transport,
            CcapiDigestTestServer server) {
        return transport.restTemplate().getForObject(
                server.baseUri() + "/ccapi", Map.class);
    }

    private CcapiDigestTestServer server(
            CcapiDigestTestServer.Mode mode,
            InetAddress address,
            String username,
            String password) throws IOException {
        CcapiDigestTestServer server = new CcapiDigestTestServer(
                mode, address, username, password);
        servers.add(server);
        return server;
    }

    private CcapiTransport transport(
            String profileId,
            CcapiDigestTestServer server,
            String username,
            String password,
            Duration readTimeout) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registries.add(registry);
        CameraConfig config = new CameraConfig(
                URI.create("http://127.0.0.1:8080"), false, Duration.ofMillis(500));
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

    private static void assertIsolated(
            CcapiDigestTestServer own,
            String expectedUsername,
            CcapiDigestTestServer other) {
        assertThat(own.authorizationParameters()).allSatisfy(parameters -> {
            assertThat(parameters).containsEntry("username", expectedUsername);
            assertThat(parameters).containsEntry("nonce", own.nonceOne());
            assertThat(parameters).containsEntry("opaque", own.opaque());
            assertThat(parameters.values()).doesNotContain(
                    other.username(), other.nonceOne(), other.opaque());
        });
    }

    private static void assertSanitized(
            Throwable failure,
            CcapiDigestTestServer server) {
        assertThat(failure.getMessage())
                .doesNotContain(server.username())
                .doesNotContain(server.password())
                .doesNotContain(server.realm())
                .doesNotContain(server.nonceOne())
                .doesNotContain(server.opaque());
    }
}
