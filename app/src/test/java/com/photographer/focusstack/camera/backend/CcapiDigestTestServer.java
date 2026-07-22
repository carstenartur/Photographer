package com.photographer.focusstack.camera.backend;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic local HTTP server for CCAPI Digest transport tests. */
final class CcapiDigestTestServer implements AutoCloseable {

    enum Mode {
        QUOTED_WITH_OPAQUE,
        QUOTED_WITHOUT_OPAQUE,
        STALE_ONCE,
        BASIC_ONLY,
        UNSUPPORTED_ALGORITHM,
        AUTH_INT_ONLY,
        SLOW_SUCCESS
    }

    static final String NONCE_ONE = "edge-nonce-one";
    static final String NONCE_TWO = "edge-nonce-two";
    private static final Pattern PARAMETER = Pattern.compile(
            "([a-zA-Z0-9_]+)=(?:\\\"([^\\\"]*)\\\"|([^,\\s]+))");

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

    CcapiDigestTestServer(
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

    URI baseUri() {
        String host = server.getAddress().getAddress() instanceof Inet6Address
                ? "[" + server.getAddress().getAddress().getHostAddress() + "]"
                : server.getAddress().getAddress().getHostAddress();
        return URI.create("http://" + host + ":" + port());
    }

    int port() {
        return server.getAddress().getPort();
    }

    int requestCount() {
        return requests.get();
    }

    String username() {
        return username;
    }

    String password() {
        return password;
    }

    String realm() {
        return realm;
    }

    String opaque() {
        return opaque;
    }

    String nonceOne() {
        return NONCE_ONE;
    }

    boolean staleChallengeSent() {
        return staleSent.get();
    }

    List<String> authorizationHeaders() {
        return List.copyOf(authorizationHeaders);
    }

    List<String> nonEmptyAuthorizationHeaders() {
        return authorizationHeaders().stream().filter(value -> !value.isEmpty()).toList();
    }

    List<Map<String, String>> authorizationParameters() {
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
