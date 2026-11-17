package com.photographer.focusstack.fresnel;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/** Private v1 integration boundary. Authenticate before reading bodies or performing conditional transfers. */
final class FresnelIngress extends OncePerRequestFilter {
    static final String ROOT = "/api/v1/integrations/fresnel";
    private static final int MAX_JSON_BYTES = 128 * 1024;
    private final FresnelPairings pairings;
    private final boolean trustNetwork;

    private final MeterRegistry metrics;

    FresnelIngress(FresnelPairings pairings, boolean trustNetwork, MeterRegistry metrics) {
        this.metrics = metrics;
        this.pairings = pairings;
        this.trustNetwork = trustNetwork;
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                             FilterChain chain) throws ServletException, IOException {
        response.setHeader("Cache-Control", "private, no-store");
        String path = request.getRequestURI().substring(request.getContextPath().length());
        try {
            if (!request.isSecure() && !trustNetwork && !loopback(request.getRemoteAddr())) {
                throw new FresnelProblem(403, "SECURE_TRANSPORT_REQUIRED");
            }
            String pairingId = null;
            if (!(path.equals(ROOT + "/pair") && request.getMethod().equals("POST"))) {
                pairingId = pairings.authenticatePeer(request.getHeader("Authorization"), request.getRemoteAddr());
            }
            if (request.getHeader("Content-Encoding") != null) throw new FresnelProblem(415, "UNSUPPORTED_ENCODING");
            if ("POST".equals(request.getMethod())) request = boundedBody(request);
            chain.doFilter(request, response);
            int status = response.getStatus();
            if (pairingId != null && ((status >= 200 && status < 300) || status == 304)) {
                pairings.recordSuccessfulRequest(pairingId);
            }
        } catch (FresnelProblem problem) {
            response.setStatus(problem.status());
            if (problem.status() == 401) response.setHeader("WWW-Authenticate", "Bearer");
            response.setContentType("application/problem+json");
            if (!"HEAD".equals(request.getMethod())) FresnelPlan.JSON.writeValue(response.getOutputStream(), problem.body());
        } finally {
            metrics.counter("photographer.fresnel.requests", "outcome", response.getStatus() / 100 + "xx").increment();
        }
    }

    static boolean loopback(String address) {
        return "127.0.0.1".equals(address) || "::1".equals(address) || "0:0:0:0:0:0:0:1".equals(address)
                || "localhost".equalsIgnoreCase(address) || "[::1]".equals(address);
    }

    private static HttpServletRequest boundedBody(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_JSON_BYTES) throw new FresnelProblem(413, "REQUEST_TOO_LARGE");
        byte[] bytes = request.getInputStream().readNBytes(MAX_JSON_BYTES + 1);
        if (bytes.length > MAX_JSON_BYTES) throw new FresnelProblem(413, "REQUEST_TOO_LARGE");
        if (bytes.length > 0) {
            try {
                MediaType type = MediaType.parseMediaType(request.getContentType() == null ? "" : request.getContentType());
                if (!MediaType.APPLICATION_JSON.equalsTypeAndSubtype(type)
                        || (type.getCharset() != null && !StandardCharsets.UTF_8.equals(type.getCharset()))) {
                    throw new FresnelProblem(415, "UNSUPPORTED_MEDIA_TYPE");
                }
            } catch (IllegalArgumentException e) {
                throw new FresnelProblem(415, "UNSUPPORTED_MEDIA_TYPE");
            }
            try {
                String utf8 = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
                var object = FresnelPlan.JSON.readTree(utf8);
                if (object == null || !object.isObject()) throw new FresnelProblem(400, "INVALID_REQUEST");
            } catch (CharacterCodingException | com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new FresnelProblem(400, "INVALID_REQUEST");
            }
        }
        return new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                var body = new ByteArrayInputStream(bytes);
                return new ServletInputStream() {
                    @Override public int read() { return body.read(); }
                    @Override public int read(byte[] b, int off, int len) { return body.read(b, off, len); }
                    @Override public boolean isFinished() { return body.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                };
            }
        };
    }
}
