package com.photographer.focusstack.ai.gemini;

import com.photographer.focusstack.ai.ProviderConfig;
import com.photographer.focusstack.ai.VisionContext;
import com.photographer.focusstack.ai.VisionFailureKind;
import com.photographer.focusstack.ai.VisionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeminiVisionProviderResilienceTest {

    @AfterEach
    void clearInterruption() {
        Thread.interrupted();
    }

    @Test
    void recoveryAdmitsOnlyOneProbeAndHonorsTheLastRetryHint() {
        var time = new java.util.concurrent.atomic.AtomicLong();
        var circuit = new GeminiCircuit(time::get);
        var unavailable = VisionResult.failure("p", "m", "t", VisionFailureKind.RATE_LIMITED,
                true, 42, "rate limited", 0);
        var late = circuit.admit();
        for (int i = 0; i < 3; i++) {
            circuit.complete(circuit.admit(), unavailable);
        }
        var success = GeminiResponseParser.parse(successBody("bird"), "p", "m", "t", 0);
        circuit.complete(late, success);
        assertEquals(42, circuit.admit().retryAfterSeconds());
        time.addAndGet(Duration.ofSeconds(42).toNanos());
        var probe = circuit.admit();
        assertTrue(probe.allowed());
        assertFalse(circuit.admit().allowed());
        circuit.complete(probe, unavailable);
        assertFalse(circuit.admit().allowed());
        time.addAndGet(Duration.ofSeconds(43).toNanos());
        circuit.complete(circuit.admit(), success);
        assertTrue(circuit.admit().allowed());
        assertTrue(circuit.admit().allowed());
    }

    @Test
    void limitsConcurrentRequestsWithoutQueuing() {
        var circuit = new GeminiCircuit(System::nanoTime);
        var first = circuit.admit();
        for (int i = 1; i < 4; i++) {
            assertTrue(circuit.admit().allowed());
        }
        assertEquals(VisionFailureKind.RATE_LIMITED, circuit.admit().failure());
        circuit.complete(first, GeminiResponseParser.parse(successBody("bird"), "p", "m", "t", 0));
        assertTrue(circuit.admit().allowed());
    }

    @Test
    void shortCircuitsConsecutiveOutagesAcrossOperations() {
        var transport = new ScriptedTransport(response(503, ""), response(503, ""),
                response(503, ""), response(200, successBody("unexpected request")));
        var provider = provider(0, transport, new RecordingSleeper());
        for (int i = 0; i < 3; i++) {
            assertFailure(provider.detectObjects(new byte[]{1}, VisionContext.wildlife()),
                    VisionFailureKind.TEMPORARY_UNAVAILABLE, true);
        }
        assertFailure(provider.describeImage(new byte[]{1}, VisionContext.wildlife()),
                VisionFailureKind.CIRCUIT_OPEN, true);
        assertEquals(3, transport.calls);
    }

    @Test
    void authenticationFailureIsPermanentSanitizedAndNotRetried() {
        ScriptedTransport transport = new ScriptedTransport(response(
                401, "secret provider payload containing token"));
        RecordingSleeper sleeper = new RecordingSleeper();
        GeminiVisionProvider provider = provider(3, transport, sleeper);

        VisionResult result = provider.classifyImage(
                new byte[]{1}, VisionContext.wildlife());

        assertFailure(result, VisionFailureKind.AUTHENTICATION, false);
        assertEquals(1, transport.calls);
        assertTrue(sleeper.delays.isEmpty());
        assertNull(result.rawResponse());
        assertFalse(result.errorMessage().contains("secret provider payload"));
    }

    @Test
    void invalidClientRequestIsPermanentAndNotRetried() {
        ScriptedTransport transport = new ScriptedTransport(
                response(400, "private validation details"));
        RecordingSleeper sleeper = new RecordingSleeper();
        GeminiVisionProvider provider = provider(99, transport, sleeper);

        VisionResult result = provider.classifyImage(
                new byte[]{1}, VisionContext.wildlife());

        assertFailure(result, VisionFailureKind.INVALID_REQUEST, false);
        assertEquals(1, transport.calls);
        assertTrue(sleeper.delays.isEmpty());
        assertFalse(result.errorMessage().contains("private validation details"));
    }

    @Test
    void rateLimitHonorsBoundedRetryHintAndRecovers() {
        ScriptedTransport transport = new ScriptedTransport(
                response(429, "private quota response", "Retry-After", "2"),
                response(200, successBody("Kohlmeise")));
        RecordingSleeper sleeper = new RecordingSleeper();
        GeminiVisionProvider provider = provider(2, transport, sleeper);

        VisionResult result = provider.classifyImage(
                new byte[]{1}, VisionContext.wildlife());

        assertTrue(result.successful());
        assertEquals("Kohlmeise", result.primaryLabel());
        assertEquals(2, transport.calls);
        assertEquals(List.of(Duration.ofSeconds(2)), sleeper.delays);
    }

    @Test
    void retryAfterIsClampedToOperationalMaximum() {
        ScriptedTransport transport = new ScriptedTransport(
                response(429, "private", "Retry-After", "9999"),
                response(200, successBody("bird")));
        RecordingSleeper sleeper = new RecordingSleeper();
        GeminiVisionProvider provider = provider(1, transport, sleeper);

        VisionResult result = provider.classifyImage(
                new byte[]{1}, VisionContext.wildlife());

        assertTrue(result.successful());
        assertEquals(List.of(Duration.ofSeconds(60)), sleeper.delays);
    }

    @Test
    void temporaryServerFailureStopsAtConfiguredBound() {
        ScriptedTransport transport = new ScriptedTransport(
                response(503, "private-1"),
                response(503, "private-2"),
                response(503, "private-3"));
        RecordingSleeper sleeper = new RecordingSleeper();
        GeminiVisionProvider provider = provider(2, transport, sleeper);

        VisionResult result = provider.classifyImage(
                new byte[]{1}, VisionContext.wildlife());

        assertFailure(result, VisionFailureKind.TEMPORARY_UNAVAILABLE, true);
        assertEquals(3, transport.calls);
        assertEquals(List.of(
                Duration.ofMillis(100), Duration.ofMillis(200)),
                sleeper.delays);
    }

    @Test
    void configuredRetryCountHasAProviderHardMaximum() {
        ScriptedTransport transport = new ScriptedTransport(
                response(503, "one"),
                response(503, "two"),
                response(503, "three"),
                response(503, "four"),
                response(503, "five"),
                response(503, "six"));
        RecordingSleeper sleeper = new RecordingSleeper();
        GeminiVisionProvider provider = provider(1000, transport, sleeper);

        VisionResult result = provider.classifyImage(
                new byte[]{1}, VisionContext.wildlife());

        assertFailure(result, VisionFailureKind.TEMPORARY_UNAVAILABLE, true);
        assertEquals(6, transport.calls, "initial attempt plus at most five retries");
        assertEquals(5, sleeper.delays.size());
    }

    @Test
    void everyAttemptUsesTheConfiguredBoundedRequestTimeout() {
        ScriptedTransport transport = new ScriptedTransport(
                response(503, "temporary"),
                response(200, successBody("bird")));
        GeminiVisionProvider provider = provider(
                1, transport, new RecordingSleeper());

        VisionResult result = provider.classifyImage(
                new byte[]{1}, VisionContext.wildlife());

        assertTrue(result.successful());
        assertEquals(2, transport.requests.size());
        for (HttpRequest request : transport.requests) {
            assertEquals(Duration.ofSeconds(1), request.timeout().orElseThrow());
        }
    }

    @Test
    void timeoutAndIoFailureAreTransientButBounded() {
        ScriptedTransport timeoutTransport = new ScriptedTransport(
                new HttpTimeoutException("private timeout details"),
                response(200, successBody("recovered")));
        GeminiVisionProvider timeoutProvider = provider(
                1, timeoutTransport, new RecordingSleeper());

        VisionResult recovered = timeoutProvider.classifyImage(
                new byte[]{1}, VisionContext.wildlife());
        assertTrue(recovered.successful());
        assertEquals(2, timeoutTransport.calls);

        ScriptedTransport ioTransport = new ScriptedTransport(
                new IOException("private host details"));
        GeminiVisionProvider ioProvider = provider(
                0, ioTransport, new RecordingSleeper());
        VisionResult unavailable = ioProvider.classifyImage(
                new byte[]{1}, VisionContext.wildlife());
        assertFailure(
                unavailable, VisionFailureKind.TEMPORARY_UNAVAILABLE, true);
        assertFalse(unavailable.errorMessage().contains("private host details"));
    }

    @Test
    void malformedSuccessResponseIsNotRetriedOrReportedAsNone() {
        ScriptedTransport transport = new ScriptedTransport(
                response(200, successOuter("not-inner-json")));
        GeminiVisionProvider provider = provider(
                3, transport, new RecordingSleeper());

        VisionResult result = provider.classifyImage(
                new byte[]{1}, VisionContext.wildlife());

        assertFailure(result, VisionFailureKind.MALFORMED_RESPONSE, false);
        assertEquals("unclassified", result.primaryLabel());
        assertEquals(1, transport.calls);
    }

    @Test
    void missingConfigurationAndInvalidImageNeverContactProvider() {
        ScriptedTransport transport = new ScriptedTransport();
        ProviderConfig missingKey = new ProviderConfig(
                null, "https://example.invalid/models", "model",
                1, 2, 60, 4);
        GeminiVisionProvider unconfigured = new GeminiVisionProvider(
                missingKey, transport, duration -> {});

        VisionResult missing = unconfigured.classifyImage(
                new byte[]{1}, VisionContext.wildlife());
        assertFailure(missing, VisionFailureKind.NOT_CONFIGURED, false);

        GeminiVisionProvider configured = provider(
                2, transport, new RecordingSleeper(), 1);
        VisionResult oversized = configured.classifyImage(
                new byte[]{1, 2}, VisionContext.wildlife());
        assertFailure(oversized, VisionFailureKind.INVALID_REQUEST, false);
        assertEquals(0, transport.calls);
    }

    @Test
    void interruptionIsPreservedAndNeverRetried() {
        ScriptedTransport transport = new ScriptedTransport(
                new InterruptedException("stop"));
        GeminiVisionProvider provider = provider(
                3, transport, new RecordingSleeper());

        VisionResult result = provider.classifyImage(
                new byte[]{1}, VisionContext.wildlife());

        assertFailure(result, VisionFailureKind.INTERRUPTED, false);
        assertTrue(Thread.currentThread().isInterrupted());
        assertEquals(1, transport.calls);
    }

    private static GeminiVisionProvider provider(
            int retries,
            ScriptedTransport transport,
            RecordingSleeper sleeper) {
        return provider(retries, transport, sleeper, 1024);
    }

    private static GeminiVisionProvider provider(
            int retries,
            ScriptedTransport transport,
            GeminiTransport.Sleeper sleeper,
            long maxImageBytes) {
        ProviderConfig config = new ProviderConfig(
                "test-key",
                "https://example.invalid/models",
                "test-model",
                1,
                retries,
                60,
                maxImageBytes);
        return new GeminiVisionProvider(config, transport, sleeper);
    }

    private static void assertFailure(
            VisionResult result,
            VisionFailureKind expected,
            boolean retryable) {
        assertFalse(result.successful());
        assertEquals(expected, result.failureKind());
        assertEquals(retryable, result.retryable());
        assertEquals("unclassified", result.primaryLabel());
        assertNull(result.rawResponse());
    }

    private static GeminiTransport.Response response(int status, String body) {
        return response(status, body, null, null);
    }

    private static GeminiTransport.Response response(
            int status,
            String body,
            String header,
            String value) {
        Map<String, List<String>> headers = header == null
                ? Map.of()
                : Map.of(header, List.of(value));
        return new GeminiTransport.Response(
                status,
                body,
                HttpHeaders.of(headers, (name, ignored) -> true));
    }

    private static String successBody(String label) {
        return successOuter("{\"primaryLabel\":\"" + label + "\","
                + "\"alternativeLabels\":[],\"confidence\":0.9,"
                + "\"summary\":\"classified\"}");
    }

    private static String successOuter(String inner) {
        String escaped = inner
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
        return "{\"candidates\":[{\"content\":{\"parts\":[{"
                + "\"text\":\"" + escaped + "\"}]}}]}";
    }

    private static final class RecordingSleeper
            implements GeminiTransport.Sleeper {
        private final List<Duration> delays = new ArrayList<>();

        @Override
        public void sleep(Duration duration) {
            delays.add(duration);
        }
    }

    private static final class ScriptedTransport implements GeminiTransport {
        private final Deque<Object> steps = new ArrayDeque<>();
        private final List<HttpRequest> requests = new ArrayList<>();
        private int calls;

        private ScriptedTransport(Object... steps) {
            this.steps.addAll(List.of(steps));
        }

        @Override
        public Response send(HttpRequest request)
                throws IOException, InterruptedException {
            calls++;
            requests.add(request);
            if (steps.isEmpty()) {
                throw new AssertionError("Unexpected Gemini transport call");
            }
            Object step = steps.removeFirst();
            if (step instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            if (step instanceof IOException ioFailure) {
                throw ioFailure;
            }
            return (Response) step;
        }
    }
}
