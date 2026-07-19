package com.photographer.focusstack.camera.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.photographer.focusstack.camera.exception.CameraAuthenticationException;
import com.photographer.focusstack.camera.exception.CameraOperationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.DefaultResponseErrorHandler;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

import static com.photographer.focusstack.camera.exception.CameraAuthenticationException.Reason.CREDENTIALS_REJECTED;
import static com.photographer.focusstack.camera.exception.CameraAuthenticationException.Reason.CREDENTIALS_REQUIRED;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.ALREADY_STARTED;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.CAMERA_REJECTED;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.CANNOT_WRITE_TO_CARD;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.DEVICE_BUSY;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.DURING_SHOOTING_OR_RECORDING;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.ENDPOINT_NOT_FOUND;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.LIVE_VIEW_NOT_STARTED;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.METHOD_NOT_ALLOWED;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.MODE_NOT_SUPPORTED;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.NOT_STARTED;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.OUT_OF_FOCUS;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.SERVICE_UNAVAILABLE;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.TAKEN_IN_PREPARATION;

/** Converts downstream CCAPI failures into typed messages without copying response data. */
final class CcapiResponseErrorHandler extends DefaultResponseErrorHandler {

    private static final int MAX_ERROR_BODY_BYTES = 4_096;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String profileId;
    private final boolean credentialsConfigured;
    private final CcapiOutcomeRecorder outcomes;

    CcapiResponseErrorHandler(
            String profileId,
            boolean credentialsConfigured,
            CcapiOutcomeRecorder outcomes) {
        this.profileId = Objects.requireNonNull(profileId, "profileId");
        this.credentialsConfigured = credentialsConfigured;
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
    }

    @Override
    protected void handleError(
            ClientHttpResponse response,
            HttpStatusCode statusCode,
            URI url,
            HttpMethod method) throws IOException {
        int status = statusCode.value();
        if (status == HttpStatus.UNAUTHORIZED.value()) {
            outcomes.record(CcapiOutcomeRecorder.Outcome.AUTH_FINAL_FAILURE);
            throw new CameraAuthenticationException(
                    profileId,
                    credentialsConfigured ? CREDENTIALS_REJECTED : CREDENTIALS_REQUIRED);
        }
        if (status == HttpStatus.NOT_FOUND.value()) {
            throw operation(ENDPOINT_NOT_FOUND, status);
        }
        if (status == HttpStatus.METHOD_NOT_ALLOWED.value()) {
            throw operation(METHOD_NOT_ALLOWED, status);
        }
        if (status == HttpStatus.SERVICE_UNAVAILABLE.value()) {
            throw operation(classifyUnavailable(readMessage(response)), status);
        }
        throw operation(CAMERA_REJECTED, status);
    }

    private CameraOperationException operation(
            CameraOperationException.Reason reason,
            int status) {
        outcomes.record(reason);
        return new CameraOperationException(profileId, reason, status);
    }

    private static CameraOperationException.Reason classifyUnavailable(String message) {
        String normalized = message == null
                ? ""
                : message.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "device busy" -> DEVICE_BUSY;
            case "during shooting or recording" -> DURING_SHOOTING_OR_RECORDING;
            case "mode not supported" -> MODE_NOT_SUPPORTED;
            case "taken in preparation" -> TAKEN_IN_PREPARATION;
            case "live view not started" -> LIVE_VIEW_NOT_STARTED;
            case "already started" -> ALREADY_STARTED;
            case "out of focus" -> OUT_OF_FOCUS;
            case "can not write to card", "cannot write to card" -> CANNOT_WRITE_TO_CARD;
            case "not started" -> NOT_STARTED;
            default -> SERVICE_UNAVAILABLE;
        };
    }

    private static String readMessage(ClientHttpResponse response) {
        try {
            byte[] bytes = response.getBody().readNBytes(MAX_ERROR_BODY_BYTES + 1);
            if (bytes.length > MAX_ERROR_BODY_BYTES) {
                return null;
            }
            JsonNode root = JSON.readTree(new String(bytes, StandardCharsets.UTF_8));
            JsonNode message = root == null ? null : root.get("message");
            return message != null && message.isTextual() ? message.textValue() : null;
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }
}
