package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.app.config.CameraConfig;
import com.photographer.focusstack.app.config.CcapiAuthenticationProperties;
import com.photographer.focusstack.camera.exception.CameraOperationException;
import com.photographer.focusstack.common.model.CameraProfile;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
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
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.CANCELLED;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.CONNECTION_FAILED;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.TIMEOUT;

/** Creates one host-scoped, optionally Digest-capable transport per Canon backend. */
@Component
public final class CcapiRestTemplateFactory {

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
    private static final String AUTH_CHALLENGED_ATTRIBUTE =
            CcapiRestTemplateFactory.class.getName() + ".authChallenged";

    private final CameraConfig cameraConfig;
    private final CcapiAuthenticationProperties authenticationProperties;
    private final CcapiOutcomeRecorder outcomes;

    public CcapiRestTemplateFactory(
            CameraConfig cameraConfig,
            CcapiAuthenticationProperties authenticationProperties,
            CcapiOutcomeRecorder outcomes) {
        this.cameraConfig = Objects.requireNonNull(cameraConfig, "cameraConfig");
        this.authenticationProperties = Objects.requireNonNull(
                authenticationProperties, "authenticationProperties");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
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
                    .setSocketTimeout(Timeout.ofMilliseconds(READ_TIMEOUT.toMillis()))
                    .build();
            PoolingHttpClientConnectionManager connectionManager =
                    PoolingHttpClientConnectionManagerBuilder.create()
                            .setDefaultConnectionConfig(connectionConfig)
                            .setMaxConnPerRoute(4)
                            .setMaxConnTotal(4)
                            .build();
            CloseableHttpClient httpClient = null;

            try {
                org.apache.hc.client5.http.impl.classic.HttpClientBuilder clientBuilder =
                        HttpClients.custom()
                                .setConnectionManager(connectionManager)
                                .disableRedirectHandling()
                                .addResponseInterceptorLast((response, entity, context) -> {
                                    int status = response.getCode();
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

                if (credentials.isPresent()) {
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
                requestFactory.setReadTimeout(READ_TIMEOUT);

                RestTemplate restTemplate = new RestTemplate(requestFactory);
                restTemplate.getInterceptors().add((request, body, execution) -> {
                    try {
                        return execution.execute(request, body);
                    } catch (SocketTimeoutException exception) {
                        throw transportFailure(profile.id(), TIMEOUT, exception);
                    } catch (ConnectException | NoRouteToHostException | UnknownHostException exception) {
                        throw transportFailure(profile.id(), CONNECTION_FAILED, exception);
                    } catch (InterruptedIOException exception) {
                        Thread.currentThread().interrupt();
                        throw transportFailure(profile.id(), CANCELLED, exception);
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
