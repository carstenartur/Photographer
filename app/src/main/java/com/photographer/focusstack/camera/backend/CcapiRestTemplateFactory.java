package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.app.config.CameraConfig;
import com.photographer.focusstack.app.config.CcapiAuthenticationProperties;
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

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Creates one host-scoped, optionally Digest-capable transport per Canon backend. */
@Component
public final class CcapiRestTemplateFactory {

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private final CameraConfig cameraConfig;
    private final CcapiAuthenticationProperties authenticationProperties;

    public CcapiRestTemplateFactory(
            CameraConfig cameraConfig,
            CcapiAuthenticationProperties authenticationProperties) {
        this.cameraConfig = Objects.requireNonNull(cameraConfig, "cameraConfig");
        this.authenticationProperties = Objects.requireNonNull(
                authenticationProperties, "authenticationProperties");
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

            try {
                org.apache.hc.client5.http.impl.classic.HttpClientBuilder clientBuilder =
                        HttpClients.custom()
                                .setConnectionManager(connectionManager)
                                .disableRedirectHandling();

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

                CloseableHttpClient httpClient = clientBuilder.build();
                HttpComponentsClientHttpRequestFactory requestFactory =
                        new HttpComponentsClientHttpRequestFactory(httpClient);
                requestFactory.setConnectionRequestTimeout(cameraConfig.connectionTimeout());
                requestFactory.setReadTimeout(READ_TIMEOUT);

                RestTemplate restTemplate = new RestTemplate(requestFactory);
                restTemplate.setErrorHandler(new CcapiResponseErrorHandler(
                        profile.id(), snapshot.state().credentialsConfigured()));
                CcapiTransport transport = new CcapiTransport(
                        restTemplate, httpClient, password);
                CcapiTransportFingerprint fingerprint = new CcapiTransportFingerprint(
                        normalizedBaseUri, snapshot.state());
                return new CcapiTransportCreation(transport, fingerprint);
            } catch (RuntimeException exception) {
                connectionManager.close();
                if (password != null) {
                    Arrays.fill(password, '\0');
                }
                throw exception;
            }
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
