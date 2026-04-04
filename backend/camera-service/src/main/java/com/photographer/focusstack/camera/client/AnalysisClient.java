package com.photographer.focusstack.camera.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.photographer.focusstack.common.analysis.AnalysisResult;
import com.photographer.focusstack.common.model.StackingParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HTTP client for the analysis-service {@code /api/analysis/analyze} endpoint.
 *
 * <p>If the analysis-service URL is not configured or the service is unreachable,
 * all methods return {@link Optional#empty()} so that camera-service can continue
 * in "dumb linear" mode without analysis feedback.
 *
 * <p>Uses Spring's auto-configured {@link RestClient.Builder} so Boot-level HTTP
 * client settings apply. Connect and read timeouts are controlled by the
 * {@code analysis.service.connect-timeout-ms} and {@code analysis.service.read-timeout-ms}
 * properties (defaults: 3 s and 10 s respectively).
 */
@Component
public class AnalysisClient {

    private static final Logger log = LoggerFactory.getLogger(AnalysisClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    /** Tracks whether a warning has already been emitted for the current connection failure. */
    private final AtomicBoolean warnedAboutFailure = new AtomicBoolean(false);

    public AnalysisClient(
            RestClient.Builder restClientBuilder,
            @Value("${analysis.service.url:}") String analysisUrl,
            @Value("${analysis.service.enabled:false}") boolean enabled,
            @Value("${analysis.service.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${analysis.service.read-timeout-ms:10000}") int readTimeoutMs,
            ObjectMapper objectMapper) {

        this.objectMapper = objectMapper;
        boolean urlConfigured = analysisUrl != null && !analysisUrl.isBlank();
        this.enabled = enabled && urlConfigured;

        if (urlConfigured) {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
            factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

            this.restClient = restClientBuilder
                    .baseUrl(analysisUrl)
                    .requestFactory(factory)
                    .build();
            log.info("Analysis client {} (url={}, connect={}ms, read={}ms)",
                    this.enabled ? "enabled" : "configured but disabled",
                    analysisUrl, connectTimeoutMs, readTimeoutMs);
        } else {
            this.restClient = restClientBuilder.build();
            log.info("Analysis client disabled – camera-service will run in linear mode");
        }
    }

    /**
     * Sends {@code imageData} (and optionally {@code previousImageData}) to the
     * analysis-service and returns the {@link AnalysisResult}.
     *
     * <p>Returns {@link Optional#empty()} if the client is disabled, if
     * {@code imageData} is {@code null}, or if the remote call fails for any reason.
     *
     * @param imageData         raw bytes of the current frame (may be {@code null})
     * @param previousImageData raw bytes of the previous frame (may be {@code null})
     * @param history           analysis results from previous frames
     * @param params            stacking parameters for the current job
     * @return analysis result wrapped in an Optional, or empty on any error
     */
    public Optional<AnalysisResult> analyzeFrame(byte[] imageData,
                                                  byte[] previousImageData,
                                                  List<AnalysisResult> history,
                                                  StackingParameters params) {
        if (!enabled || imageData == null || imageData.length == 0) {
            return Optional.empty();
        }

        try {
            String requestJson = objectMapper.writeValueAsString(
                    new AnalyzeRequestPayload(params, history));

            MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
            parts.add("image", new NamedByteArrayResource("frame.bin", imageData));
            if (previousImageData != null && previousImageData.length > 0) {
                parts.add("previousFrame",
                        new NamedByteArrayResource("previous.bin", previousImageData));
            }
            parts.add("request", requestJson);

            AnalysisResult result = restClient.post()
                    .uri("/api/analysis/analyze")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(parts)
                    .retrieve()
                    .body(AnalysisResult.class);

            // Service responded successfully — reset the "warned" flag
            warnedAboutFailure.set(false);
            return Optional.ofNullable(result);
        } catch (RestClientException e) {
            if (warnedAboutFailure.compareAndSet(false, true)) {
                log.warn("Analysis-service unreachable, continuing in linear mode: {}", e.getMessage());
            } else {
                log.debug("Analysis-service still unreachable: {}", e.getMessage());
            }
            return Optional.empty();
        } catch (Exception e) {
            log.error("Unexpected error calling analysis-service: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    /** Simple DTO matching {@code AnalyzeRequest} in the analysis-service. */
    private record AnalyzeRequestPayload(
            StackingParameters parameters,
            List<AnalysisResult> history) {}

    /** ByteArrayResource subclass that supplies a filename for multipart uploads. */
    private static final class NamedByteArrayResource extends ByteArrayResource {
        private final String filename;

        NamedByteArrayResource(String filename, byte[] byteArray) {
            super(byteArray);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}
