package com.photographer.focusstack.fresnel;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** The externally triggered still-image plan from the pinned v1 contract. */
record FresnelPlan(String externalExperimentId, String cameraProfileId, Workflow workflowType,
                   Settings requestedSettings, List<Step> steps) {
    enum Workflow { BACKGROUND_ORIENTED_SCHLIEREN, DISPLAY_PHOTOMETRIC_STEREO }
    enum Role { REFERENCE, DISTURBED, DARK, FLAT, DIRECTIONAL_LIGHT }
    record Settings(Boolean lockExposure, Boolean lockFocus, String preferredFormat) {}
    record Step(String id, Integer sequenceIndex, Role role, String triggerMode, Integer settleDelayMs,
                String externalPatternId, String externalPatternSha256) {}
    record Trigger(String presentedAt, String externalPatternId, String externalPatternSha256) {}

    static final int MAX_STEPS = 32;
    static final int MAX_DURATION_MS = 600_000;
    static final int MAX_WAIT_MS = 60_000;
    static final int MAX_ASSET_BYTES = 8 * 1024 * 1024;
    static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .withCoercionConfig(LogicalType.Textual, rules -> rules
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .serializationInclusion(JsonInclude.Include.NON_NULL).build();
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern SHA = Pattern.compile("[a-f0-9]{64}");

    static <T> T parse(byte[] body, Class<T> type) {
        try {
            T value = JSON.readValue(body, type);
            if (value != null) return value;
        } catch (IOException e) {
            throw new FresnelProblem(400, "INVALID_REQUEST");
        }
        throw new FresnelProblem(400, "INVALID_REQUEST");
    }

    static void requireId(String id) {
        if (id == null || !ID.matcher(id).matches()) throw new FresnelProblem(400, "INVALID_REQUEST");
    }

    static String hash(byte[] bytes) {
        return HexFormat.of().formatHex(sha256().digest(bytes));
    }

    static String hash(Path path) throws IOException {
        var digest = sha256();
        try (var input = new DigestInputStream(Files.newInputStream(path), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    void validate() {
        requireId(externalExperimentId);
        requireId(cameraProfileId);
        if (workflowType == null || requestedSettings == null || requestedSettings.lockFocus == null
                || requestedSettings.lockExposure == null || requestedSettings.preferredFormat == null
                || steps == null)
            throw new FresnelProblem(400, "INVALID_REQUEST");
        if (steps.isEmpty() || steps.size() > MAX_STEPS) throw new FresnelProblem(422, "INVALID_PLAN");
        if (!Set.of("JPEG", "PNG", "RAW_WITH_PREVIEW").contains(requestedSettings.preferredFormat))
            throw new FresnelProblem(400, "INVALID_REQUEST");
        int delay = 0, references = 0, lights = 0;
        var ids = new HashSet<String>();
        var patterns = new HashMap<String,String>();
        boolean bos = workflowType == Workflow.BACKGROUND_ORIENTED_SCHLIEREN;
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            if (step == null) throw new FresnelProblem(400, "INVALID_REQUEST");
            requireId(step.id);
            if (step.id.length() > 64 || step.sequenceIndex == null || step.sequenceIndex < 0 || step.sequenceIndex > 255
                    || step.role == null || !"EXTERNAL".equals(step.triggerMode) || step.settleDelayMs == null
                    || step.settleDelayMs < 0 || step.settleDelayMs > 30_000)
                throw new FresnelProblem(400, "INVALID_REQUEST");
            if (!ids.add(step.id) || step.sequenceIndex != i) throw new FresnelProblem(422, "INVALID_PLAN");
            delay += step.settleDelayMs;
            if (!bos || step.externalPatternId != null || step.externalPatternSha256 != null) {
                requireId(step.externalPatternId);
                if (step.externalPatternSha256 == null || !SHA.matcher(step.externalPatternSha256).matches())
                    throw new FresnelProblem(400, "INVALID_REQUEST");
                String old = patterns.putIfAbsent(step.externalPatternId, step.externalPatternSha256);
                if (old != null && !old.equals(step.externalPatternSha256)) throw new FresnelProblem(422, "INVALID_PLAN");
            }
            if (bos != (step.role == Role.REFERENCE || step.role == Role.DISTURBED))
                throw new FresnelProblem(422, "INVALID_PLAN");
            if (step.role == Role.REFERENCE) references++;
            if (step.role == Role.DIRECTIONAL_LIGHT) lights++;
        }
        if (delay > MAX_DURATION_MS || (bos ? references != 1 || steps.size() < 2 : lights < 3))
            throw new FresnelProblem(422, "INVALID_PLAN");
    }

    static void validateTrigger(Trigger trigger, Step step) {
        try {
            OffsetDateTime.parse(trigger.presentedAt());
        } catch (DateTimeParseException | NullPointerException invalid) {
            throw new FresnelProblem(400, "INVALID_REQUEST");
        }
        if (trigger.externalPatternId() != null || trigger.externalPatternSha256() != null) {
            requireId(trigger.externalPatternId());
            if (trigger.externalPatternSha256() == null || !SHA.matcher(trigger.externalPatternSha256()).matches())
                throw new FresnelProblem(400, "INVALID_REQUEST");
        }
        if (!java.util.Objects.equals(trigger.externalPatternId(), step.externalPatternId())
                || !java.util.Objects.equals(trigger.externalPatternSha256(), step.externalPatternSha256()))
            throw new FresnelProblem(422, "PATTERN_MISMATCH");
    }
}
