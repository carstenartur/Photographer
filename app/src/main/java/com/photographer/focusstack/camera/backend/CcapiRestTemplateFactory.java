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
import java.time.Duration;
import java.util.Arrays;
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

    CcapiTransport create(CameraProfile profile, URI baseUri) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(baseUri, "baseUri");
        if (baseUri.getHost() == null) {
            throw new IllegalArgumentException("CCAPI base URL must contain a host");
        }

        Optional<CcapiAuthenticationProperties.ResolvedCredentials> credentials =
                authenticationProperties.resolve(profile.id());
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
                CcapiAuthenticationProperties.ResolvedCredentials value = credentials.orElseThrow();
                password = value.passwordCopy();
                BasicCredentialsProvider provider = new BasicCredentialsProvider();
                // UsernamePasswordCredentials retains this character array and needs it when a
                // later 401 challenge is answered. The CcapiTransport owns and clears the array
                // only after its HttpClient has been closed.
                provider.setCredentials(
                        new AuthScope(baseUri.getHost(), effectivePort(baseUri)),
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
                    profile.id(), credentials.isPresent()));
            return new CcapiTransport(restTemplate, httpClient, password);
        } catch (RuntimeException exception) {
            connectionManager.close();
            if (password != null) {
                Arrays.fill(password, '\0');
            }
            throw exception;
        }
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
