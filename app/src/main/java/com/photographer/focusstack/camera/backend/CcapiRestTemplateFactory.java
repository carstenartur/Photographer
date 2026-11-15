package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.app.config.CameraConfig;
import com.photographer.focusstack.app.config.CcapiAuthenticationProperties;
import com.photographer.focusstack.camera.exception.CameraAuthenticationException;
import com.photographer.focusstack.camera.exception.CameraOperationException;
import com.photographer.focusstack.common.model.CameraProfile;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.StandardAuthScheme;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.Method;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http.protocol.HttpCoreContext;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.photographer.focusstack.camera.exception.CameraAuthenticationException.Reason.CREDENTIALS_REJECTED;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.CANCELLED;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.CONNECTION_FAILED;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.TIMEOUT;

/** Creates one host-scoped, Digest-only transport per Canon backend. */
@Component
public final class CcapiRestTemplateFactory {

    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);
    private static final String AUTH_CHALLENGED_ATTRIBUTE =
            CcapiRestTemplateFactory.class.getName() + ".authChallenged";
    private static final Pattern CHALLENGE_PARAMETER = Pattern.compile(
            "(?i)(?:^|,)\\s*([a-zA-Z0-9_-]+)\\s*=\\s*(?:\"([^\"]*)\"|([^,\\s]+))");

    // HttpClient's response retry can otherwise replay a shutter POST after 503.
    // CCAPI operations decide explicitly when a non-idempotent command can be retried.
    private static final DefaultHttpRequestRetryStrategy RETRY_IDEMPOTENT_RESPONSES =
            new DefaultHttpRequestRetryStrategy() {
                @Override
                public boolean retryRequest(HttpResponse response, int execCount, HttpContext context) {
                    HttpRequest request = HttpCoreContext.castOrCreate(context).getRequest();
                    return request != null && Method.isIdempotent(request.getMethod())
                            && super.retryRequest(response, execCount, context);
                }
            };

    private final CameraConfig cameraConfig;
    private final CcapiAuthenticationProperties authenticationProperties;
    private final CcapiOutcomeRecorder outcomes;
    private final Duration readTimeout;

    @org.springframework.beans.factory.annotation.Autowired
    public CcapiRestTemplateFactory(
            CameraConfig cameraConfig,
            CcapiAuthenticationProperties authenticationProperties,
            CcapiOutcomeRecorder outcomes) {
        this(cameraConfig, authenticationProperties, outcomes, DEFAULT_READ_TIMEOUT);
    }

    CcapiRestTemplateFactory(
            CameraConfig cameraConfig,
            CcapiAuthenticationProperties authenticationProperties,
            CcapiOutcomeRecorder outcomes,
            Duration readTimeout) {
        this.cameraConfig = Objects.requireNonNull(cameraConfig, "cameraConfig");
        this.authenticationProperties = Objects.requireNonNull(
                authenticationProperties, "authenticationProperties");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
        this.readTimeout = requirePositive(readTimeout, "readTimeout");
    }

    /** Returns a secret-free view of the transport configuration for cache matching. */
    CcapiTransportFingerprint fingerprint(CameraProfile profile, URI baseUri) {
        Objects.requireNonNull(profile, "profile");
        URI normalizedBaseUri = normalizeBaseUri(baseUri);
        return new CcapiTransportFingerprint(
                normalizedBaseUri,
                authenticationProperties.state(profile.id()));
    }

    /** Creates a transport for callers that do not need its cache fingerprint. */
    CcapiTransport create(CameraProfile profile, URI baseUri) {
        return createWithFingerprint(profile, baseUri).transport();
    }

    /** Creates a transport and returns the exact configuration snapshot it consumed. */
    CcapiTransportCreation createWithFingerprint(CameraProfile profile, URI baseUri) {
        Objects.requireNonNull(profile, "profile");
        URI normalizedBaseUri = normalizeBaseUri(baseUri);

        try (CcapiAuthenticationProperties.CredentialSnapshot snapshot =
                     authenticationProperties.snapshot(profile.id())) {
            Optional<CcapiAuthenticationProperties.ResolvedCredentials> credentials =
                    snapshot.credentials();
            char[] password = null;

            ConnectionConfig connectionConfig = ConnectionConfig.custom()
                    .setConnectTimeout(Timeout.ofMilliseconds(
                            cameraConfig.connectionTimeout().toMillis()))
                    .setSocketTimeout(Timeout.ofMilliseconds(readTimeout.toMillis()))
                    .build();
            RequestConfig requestConfig = RequestConfig.custom()
                    .setTargetPreferredAuthSchemes(List.of(StandardAuthScheme.DIGEST))
                    .setConnectionRequestTimeout(Timeout.ofMilliseconds(
                            cameraConfig.connectionTimeout().toMillis()))
                    .setResponseTimeout(Timeout.ofMilliseconds(readTimeout.toMillis()))
                    .build();
            PoolingHttpClientConnectionManager connectionManager =
                    PoolingHttpClientConnectionManagerBuilder.create()
                            .setDefaultConnectionConfig(connectionConfig)
                            .setMaxConnPerRoute(4)
                            .setMaxConnTotal(4)
                            .build();
            CloseableHttpClient httpClient = null;

            try {
                boolean credentialsConfigured = credentials.isPresent();
                org.apache.hc.client5.http.impl.classic.HttpClientBuilder clientBuilder =
                        HttpClients.custom()
                                .setConnectionManager(connectionManager)
                                .setDefaultRequestConfig(requestConfig)
                                .setRetryStrategy(RETRY_IDEMPOTENT_RESPONSES)
                                .disableRedirectHandling()
                                .addResponseInterceptorLast((response, entity, context) -> {
                                    int status = response.getCode();
                                    if (status == 401 && credentialsConfigured) {
                                        validateCanonDigestChallenge(profile.id(), response.getHeaders(
                                                "WWW-Authenticate"));
                                    }
                                    if (status == 401
                                            && !Boolean.TRUE.equals(context.getAttribute(
                                            AUTH_CHALLENGED_ATTRIBUTE))) {
                                        context.setAttribute(AUTH_CHALLENGED_ATTRIBUTE, Boolean.TRUE);
                                        outcomes.record(CcapiOutcomeRecorder.Outcome.AUTH_CHALLENGE);
                                    } else if (status >= 200 && status < 300
                                            && Boolean.TRUE.equals(context.getAttribute(
                                            AUTH_CHALLENGED_ATTRIBUTE))) {
                                        context.removeAttribute(AUTH_CHALLENGED_ATTRIBUTE);
                                        outcomes.record(
                                                CcapiOutcomeRecorder.Outcome.AUTH_SUCCESS_AFTER_CHALLENGE);
                                    }
                                });

                if (credentialsConfigured) {
                    CcapiAuthenticationProperties.ResolvedCredentials value =
                            credentials.orElseThrow();
                    password = value.passwordCopy();
                    BasicCredentialsProvider provider = new BasicCredentialsProvider();
                    // UsernamePasswordCredentials retains this character array and needs it when a
                    // later 401 challenge is answered. The CcapiTransport owns and clears the array
                    // only after its HttpClient has been closed.
                    provider.setCredentials(
                            new AuthScope(
                                    normalizedBaseUri.getHost(),
                                    effectivePort(normalizedBaseUri)),
                            new UsernamePasswordCredentials(value.username(), password));
                    clientBuilder.setDefaultCredentialsProvider(provider);
                }

                httpClient = clientBuilder.build();
                HttpComponentsClientHttpRequestFactory requestFactory =
                        new HttpComponentsClientHttpRequestFactory(httpClient);
                requestFactory.setConnectionRequestTimeout(cameraConfig.connectionTimeout());
                requestFactory.setReadTimeout(readTimeout);

                RestTemplate restTemplate = new RestTemplate(requestFactory);
                restTemplate.getInterceptors().add((request, body, execution) -> {
                    try {
                        return execution.execute(request, body);
                    } catch (SocketTimeoutException exception) {
                        throw transportFailure(profile.id(), TIMEOUT, exception);
                    } catch (ConnectException | NoRouteToHostException | UnknownHostException exception) {
                        throw transportFailure(profile.id(), CONNECTION_FAILED, exception);
                    } catch (InterruptedIOException exception) {
                        CameraOperationException.Reason reason = Thread.currentThread().isInterrupted()
                                ? CANCELLED
                                : TIMEOUT;
                        throw transportFailure(profile.id(), reason, exception);
                    } catch (IOException exception) {
                        CameraOperationException.Reason reason = Thread.currentThread().isInterrupted()
                                ? CANCELLED
                                : CONNECTION_FAILED;
                        throw transportFailure(profile.id(), reason, exception);
                    }
                });
                restTemplate.setErrorHandler(new CcapiResponseErrorHandler(
                        profile.id(), snapshot.state().credentialsConfigured(), outcomes));
                CcapiTransport transport = new CcapiTransport(
                        restTemplate, httpClient, password);
                CcapiTransportFingerprint fingerprint = new CcapiTransportFingerprint(
                        normalizedBaseUri, snapshot.state());
                return new CcapiTransportCreation(transport, fingerprint);
            } catch (RuntimeException exception) {
                closeFailedConstruction(httpClient, connectionManager, exception);
                if (password != null) {
                    Arrays.fill(password, '\0');
                }
                throw exception;
            }
        }
    }

    private void validateCanonDigestChallenge(String profileId, Header[] challenges) {
        if (hasCompatibleCanonDigestChallenge(challenges)) {
            return;
        }
        if (containsDigestChallenge(challenges)) {
            outcomes.record(CcapiOutcomeRecorder.Outcome.AUTH_FINAL_FAILURE);
            throw new CameraAuthenticationException(profileId, CREDENTIALS_REJECTED);
        }
        // A Basic-only or otherwise non-Digest challenge is not answered because
        // RequestConfig permits Digest only. The bounded final 401 is translated by
        // CcapiResponseErrorHandler without exposing challenge material.
    }

    static boolean hasCompatibleCanonDigestChallenge(Header[] challenges) {
        for (Header challenge : challenges) {
            String value = challenge.getValue();
            if (!isDigestChallenge(value)) {
                continue;
            }
            String parameters = value.substring(7);
            String algorithm = parameter(parameters, "algorithm").orElse("MD5");
            String qop = parameter(parameters, "qop").orElse("");
            boolean supportsAuth = Arrays.stream(qop.split(","))
                    .map(String::trim)
                    .anyMatch("auth"::equalsIgnoreCase);
            if ("MD5".equalsIgnoreCase(algorithm) && supportsAuth) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsDigestChallenge(Header[] challenges) {
        return Arrays.stream(challenges)
                .map(Header::getValue)
                .anyMatch(CcapiRestTemplateFactory::isDigestChallenge);
    }

    private static boolean isDigestChallenge(String value) {
        return value != null && value.regionMatches(true, 0, "Digest ", 0, 7);
    }

    private static Optional<String> parameter(String challenge, String name) {
        Matcher matcher = CHALLENGE_PARAMETER.matcher(challenge);
        while (matcher.find()) {
            if (name.equalsIgnoreCase(matcher.group(1))) {
                return Optional.ofNullable(matcher.group(2) != null
                        ? matcher.group(2)
                        : matcher.group(3));
            }
        }
        return Optional.empty();
    }

    private CameraOperationException transportFailure(
            String profileId,
            CameraOperationException.Reason reason,
            IOException cause) {
        outcomes.record(reason);
        return new CameraOperationException(profileId, reason, null, cause);
    }

    private static void closeFailedConstruction(
            CloseableHttpClient httpClient,
            PoolingHttpClientConnectionManager connectionManager,
            RuntimeException originalFailure) {
        if (httpClient == null) {
            connectionManager.close();
            return;
        }
        try {
            httpClient.close();
        } catch (IOException closeFailure) {
            originalFailure.addSuppressed(closeFailure);
        }
    }

    static URI normalizeBaseUri(URI baseUri) {
        Objects.requireNonNull(baseUri, "baseUri");
        String scheme = baseUri.getScheme();
        String host = baseUri.getHost();
        if (scheme == null || host == null) {
            throw new IllegalArgumentException("CCAPI base URL must contain a scheme and host");
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("CCAPI base URL must use HTTP or HTTPS");
        }
        if (baseUri.getUserInfo() != null
                || baseUri.getQuery() != null
                || baseUri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "CCAPI base URL must not contain user info, a query or a fragment");
        }

        String path = baseUri.normalize().getPath();
        if (path == null || path.isBlank() || "/".equals(path)) {
            path = "";
        } else {
            while (path.endsWith("/") && path.length() > 1) {
                path = path.substring(0, path.length() - 1);
            }
        }

        try {
            return new URI(
                    scheme,
                    null,
                    host.toLowerCase(Locale.ROOT),
                    effectivePort(baseUri),
                    path,
                    null,
                    null);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Invalid CCAPI base URL", exception);
        }
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}

record CcapiTransportFingerprint(
        URI baseUri,
        CcapiAuthenticationProperties.CredentialState credentialState) {

    CcapiTransportFingerprint {
        Objects.requireNonNull(baseUri, "baseUri");
        Objects.requireNonNull(credentialState, "credentialState");
    }
}

record CcapiTransportCreation(
        CcapiTransport transport,
        CcapiTransportFingerprint fingerprint) {

    CcapiTransportCreation {
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(fingerprint, "fingerprint");
    }
}
