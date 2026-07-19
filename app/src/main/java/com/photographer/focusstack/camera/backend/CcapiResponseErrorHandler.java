package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.camera.exception.CameraAuthenticationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.DefaultResponseErrorHandler;

import java.io.IOException;
import java.net.URI;
import java.util.Objects;

import static com.photographer.focusstack.camera.exception.CameraAuthenticationException.Reason.CREDENTIALS_REJECTED;
import static com.photographer.focusstack.camera.exception.CameraAuthenticationException.Reason.CREDENTIALS_REQUIRED;

/** Converts a final downstream 401 into a typed message without copying challenge data. */
final class CcapiResponseErrorHandler extends DefaultResponseErrorHandler {

    private final String profileId;
    private final boolean credentialsConfigured;

    CcapiResponseErrorHandler(String profileId, boolean credentialsConfigured) {
        this.profileId = Objects.requireNonNull(profileId, "profileId");
        this.credentialsConfigured = credentialsConfigured;
    }

    @Override
    protected void handleError(
            ClientHttpResponse response,
            HttpStatusCode statusCode,
            URI url,
            HttpMethod method) throws IOException {
        if (statusCode.value() == HttpStatus.UNAUTHORIZED.value()) {
            throw new CameraAuthenticationException(
                    profileId,
                    credentialsConfigured ? CREDENTIALS_REJECTED : CREDENTIALS_REQUIRED);
        }
        super.handleError(response, statusCode, url, method);
    }
}
