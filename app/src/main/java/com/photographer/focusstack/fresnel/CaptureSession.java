package com.photographer.focusstack.fresnel;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class CaptureSession {
    enum State {
        WAITING_FOR_EXTERNAL_TRIGGER, CAPTURING, PAUSED, COMPLETED, CANCELLED, RECOVERY_REQUIRED;
        boolean terminal() { return this == COMPLETED || this == CANCELLED; }
    }
    enum StepState { PENDING, CAPTURING, COMPLETED, CANCELLED, RECOVERY_REQUIRED }
    record Asset(String sessionId, String stepId, String assetId, String role, String originalFileName,
                 String mediaType, long byteLength, String sha256, String capturedAt) {}
    record Result(String sessionId, String stepId, Asset asset, String completedAt) {}
    record Event(long eventId, String sessionId, String state, String messageCode, String timestamp,
                 String stepId, Asset asset) {}
    static final class Step {
        public String stepId;
        public int sequenceIndex;
        public StepState state = StepState.PENDING;
        public String key;
        public String bodyHash;
        public boolean dispatched;
        public String assetId;
        public Result result;
        public boolean verified = true;

        Map<String,Object> status() {
            var out = new LinkedHashMap<String,Object>();
            out.put("stepId", stepId);
            out.put("sequenceIndex", sequenceIndex);
            out.put("state", state);
            if (result != null && verified) out.put("result", result);
            return out;
        }
    }

    public String id;
    public String pairingId;
    public String createKey;
    public String bodyHash;
    public String internalCameraId;
    public String createdAt;
    public String updatedAt;
    public State state;
    public boolean purging;
    public FresnelPlan plan;
    public java.util.Set<String> cancelKeys = new java.util.LinkedHashSet<>();
    public List<Step> steps = new ArrayList<>();
    public List<Event> events = new ArrayList<>();

    static CaptureSession create(String pairing, String key, String hash, String camera, FresnelPlan plan) {
        var session = new CaptureSession();
        session.id = UUID.randomUUID().toString();
        session.pairingId = pairing;
        session.createKey = key;
        session.bodyHash = hash;
        session.internalCameraId = camera;
        session.createdAt = Instant.now().toString();
        session.plan = plan;
        for (FresnelPlan.Step planned : plan.steps()) {
            var step = new Step();
            step.stepId = planned.id();
            step.sequenceIndex = planned.sequenceIndex();
            session.steps.add(step);
        }
        session.event(State.WAITING_FOR_EXTERNAL_TRIGGER, "SESSION_READY", null);
        return session;
    }

    void event(State next, String code, Step current) {
        state = next;
        updatedAt = Instant.now().toString();
        events.add(new Event(events.size() + 1L, id, next.name(), code, updatedAt,
                current == null ? null : current.stepId,
                current == null || current.result == null || !current.verified ? null : current.result.asset));
    }

    Map<String,Object> created() {
        var effective = Map.of("format", plan.requestedSettings().preferredFormat(), "lockExposure", false, "lockFocus", false);
        return Map.of("sessionId", id, "cameraProfileId", plan.cameraProfileId(),
                "createdAt", createdAt, "effectiveSettings", effective);
    }

    Step step(String stepId) {
        return steps.stream().filter(step -> step.stepId.equals(stepId)).findFirst()
                .orElseThrow(() -> new FresnelProblem(404, "STEP_NOT_FOUND"));
    }

    long completed() { return steps.stream().filter(step -> step.state == StepState.COMPLETED).count(); }

    Map<String,Object> status() {
        return Map.of("sessionId", id, "state", state, "totalSteps", steps.size(), "completedSteps", completed(),
                "updatedAt", updatedAt, "lastEventId", events.size(), "steps", steps.stream().map(Step::status).toList());
    }

    Map<String,Object> active() {
        var out = new LinkedHashMap<String,Object>();
        out.put("sessionId", id);
        out.put("cameraProfileId", plan.cameraProfileId());
        out.put("state", state);
        out.put("completedSteps", completed());
        out.put("totalSteps", steps.size());
        steps.stream().filter(step -> step.state != StepState.COMPLETED).findFirst()
                .ifPresent(step -> out.put("currentStepId", step.stepId));
        return out;
    }
}
