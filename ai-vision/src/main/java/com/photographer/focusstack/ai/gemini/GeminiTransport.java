package com.photographer.focusstack.ai.gemini;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Internal HTTP seam used to test Gemini behavior without external network access. */
@FunctionalInterface
interface GeminiTransport {

    Response send(HttpRequest request) throws IOException, InterruptedException;

    record Response(int statusCode, String body, HttpHeaders headers) {
        Response {
            body = body == null ? "" : body;
            headers = headers == null ? HttpHeaders.of(java.util.Map.of(), (a, b) -> true)
                    : headers;
        }

        String firstHeader(String name) {
            return headers.firstValue(name).orElse(null);
        }
    }

    static GeminiTransport jdk(Duration connectTimeout) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        return request -> {
            HttpResponse<String> response = client.send(
                    request, HttpResponse.BodyHandlers.ofString());
            return new Response(
                    response.statusCode(), response.body(), response.headers());
        };
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;

        static Sleeper threadSleep() {
            return duration -> Thread.sleep(duration.toMillis());
        }
    }
}
