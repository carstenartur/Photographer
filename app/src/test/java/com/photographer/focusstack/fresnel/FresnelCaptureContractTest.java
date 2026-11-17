package com.photographer.focusstack.fresnel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.photographer.focusstack.camera.backend.CameraBackendFactory;
import com.photographer.focusstack.camera.backend.MockCameraBackend;
import com.photographer.focusstack.camera.service.CameraProfileService;
import com.photographer.focusstack.camera.service.FrameDataStore;
import com.photographer.focusstack.camera.session.*;
import com.photographer.focusstack.common.camera.CaptureResult;
import com.photographer.focusstack.common.model.CameraProfile;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FresnelCaptureContractTest {
    @TempDir Path directory;
    private static final Path CONTRACT = Path.of("..", "docs", "integrations", "fresnel-v1");
    private static final String NAME = "photographer-capture-v1.";
    private final CameraProfile profile = new CameraProfile("private/mock", "Private display", "mock", null, 2, 30, .1);
    private final DefaultCameraLeaseIdentityProvider identities = new DefaultCameraLeaseIdentityProvider(true);
    private final InMemoryCameraSessionLeaseManager leases = new InMemoryCameraSessionLeaseManager(Duration.ofHours(1), 8);
    private final AtomicInteger count = new AtomicInteger();
    private final CameraProfileService profiles = mock(CameraProfileService.class);
    private final CameraBackendFactory backends = mock(CameraBackendFactory.class);
    private FresnelPairings pairings;
    private FresnelCaptures captures;
    private FrameDataStore media;
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private JsonNode fixtures;
    private FresnelPairings.Exchange exchange;
    private String token;

    @BeforeEach void open() throws Exception {
        fixtures = FresnelPlan.JSON.readTree(CONTRACT.resolve(NAME + "fixtures.json").toFile()).get("values");
        when(profiles.getAllProfiles()).thenReturn(List.of(profile));
        when(backends.getBackend(profile)).thenReturn(new MockCameraBackend(0) {
            @Override public CaptureResult capture() { count.incrementAndGet(); return super.capture(); }
        });
        pairings = new FresnelPairings(directory, Clock.systemUTC());
        var grant = pairings.issue("http://localhost", FresnelPairings.SCOPES);
        exchange = pairings.exchange(new FresnelPairings.PairRequest(grant.pairingCode(), "fixture", "Fresnel", "fixture.1", 1), "loopback");
        token = "Bearer " + exchange.bearerToken();
        media = new FrameDataStore(directory.resolve("frames").toString(), 4096);
        start();
    }

    private void start() throws Exception {
        captures = new FresnelCaptures(profiles, backends, identities, leases, pairings, media, metrics, directory, Duration.ZERO);
    }

    @AfterEach void close() throws Exception {
        captures.close();
        leases.close();
        metrics.close();
    }

    @Test void upstreamBytesArePinned() throws Exception {
        assertThat(FresnelPlan.hash(Files.readAllBytes(CONTRACT.resolve(NAME + "openapi.yaml"))))
                .isEqualTo("6cf3676511ea4ba4ddcbc5805bf5052c4e41390df0e0af59ce9f6ae948464afa");
        assertThat(FresnelPlan.hash(Files.readAllBytes(CONTRACT.resolve(NAME + "fixtures.json"))))
                .isEqualTo("0c674f2e61ed79c4f3ce2c09559a726fe740afef2463b4ea73a6b79d062d6692");
        assertThat(FresnelPlan.hash(Files.readAllBytes(CONTRACT.resolve(NAME + "md"))))
                .isEqualTo("5b1a77d460fdb900cd375a9c96dd9d204913aae1f51b813640ec56139908b973");
    }

    @Test void authorizedPlanUsesOneSharedLeaseAndDeliversVerifiedReplayAndRanges() throws Exception {
        String owner = exchange.pairingId();
        byte[] plan = plan("bosPlan");
        var created = captures.create(owner, "create", plan);
        assertThat(captures.create(owner, "create", plan)).isEqualTo(created);
        String sid = (String) created.get("sessionId");
        assertThat(leases.metrics().activeLeases()).isEqualTo(1);
        assertThatThrownBy(() -> leases.acquire(new CameraLeaseRequest(identities.resolve(profile).orElseThrow(),
                CameraWorkflowType.DIRECT_CONTROL, "other", "other", Duration.ofMinutes(1))))
                .isInstanceOf(CameraSessionConflictException.class);
        assertThat(count.get()).isZero();
        var reference = complete(owner, sid, "reference", bytes("bosTrigger"));
        assertThat(leases.metrics().activeLeases()).isEqualTo(1);
        var replay = captures.trigger(owner, sid, "reference", "reference", bytes("bosTrigger"));
        assertThat(replay.body()).isEqualTo(reference);
        assertThatThrownBy(() -> captures.trigger(owner, sid, "disturbed", "reference", bytes("bosTrigger")))
                .isInstanceOf(FresnelProblem.class).hasMessage("IDEMPOTENCY_CONFLICT");
        assertThat(count.get()).isOne();
        complete(owner, sid, "disturbed", bytes("disturbedTrigger"));
        assertThat(count.get()).isEqualTo(2);
        assertThat(leases.metrics().activeLeases()).isZero();

        assertThat(metrics.get("photographer.fresnel.step.transitions").tag("state", "COMPLETED").counter().count())
                .isEqualTo(2);
        assertThat(metrics.get("photographer.fresnel.session.transitions").tag("state", "COMPLETED").counter().count())
                .isEqualTo(1);
        assertThat(metrics.get("photographer.fresnel.capture.source.bytes").counter().count())
                .isGreaterThan(reference.asset().byteLength());
        String assetId = reference.asset().assetId();
        assertThatThrownBy(() -> captures.asset("unrelated", sid, assetId))
                .isInstanceOf(FresnelProblem.class).hasMessage("ASSET_NOT_FOUND");
        var controller = new FresnelCaptureController(captures, pairings);
        var req = new MockHttpServletRequest("GET", "/");
        var full = controller.asset(token, sid, assetId, req);
        byte[] expected = responseBytes(full.getBody());
        assertThat(FresnelPlan.hash(expected)).isEqualTo(reference.asset().sha256());
        req.addHeader("Range", "bytes=3-15");
        req.addHeader("If-Range", full.getHeaders().getETag());
        var partial = controller.asset(token, sid, assetId, req);
        assertThat(partial.getStatusCode().value()).isEqualTo(206);
        assertThat(responseBytes(partial.getBody())).isEqualTo(Arrays.copyOfRange(expected, 3, 16));
        req.addHeader("If-None-Match", "\"other\", W/" + full.getHeaders().getETag());
        assertThat(controller.asset(token, sid, assetId, req).getStatusCode().value()).isEqualTo(304);
        req.removeHeader("If-None-Match");
        req.removeHeader("If-Range");
        req.addHeader("If-Range", "\"stale\"");
        assertThat(controller.asset(token, sid, assetId, req).getStatusCode().value()).isEqualTo(200);

        captures.close();
        start();
        assertThat(captures.status(owner, sid).get("state").toString()).isEqualTo("COMPLETED");
        assertThat(captures.trigger(owner, sid, "reference", "reference", bytes("bosTrigger")).body()).isEqualTo(reference);
        assertThat(count.get()).isEqualTo(2);
        pairings.revoke(owner, () -> captures.revoke(owner));
        assertThatThrownBy(() -> controller.asset(token, sid, assetId, req))
                .isInstanceOf(FresnelProblem.class).hasMessage("AUTHENTICATION_FAILED");
    }

    @Test void restartBetweenStepsFencesUnreservedAndKeepsOnlyVerifiedCommittedResults() throws Exception {
        String owner = exchange.pairingId();
        String sid = (String) captures.create(owner, "create", plan("bosPlan")).get("sessionId");
        var reference = complete(owner, sid, "reference", bytes("bosTrigger"));
        captures.close();
        start();
        assertThat(captures.status(owner, sid).get("state").toString()).isEqualTo("RECOVERY_REQUIRED");
        assertThat(captures.trigger(owner, sid, "reference", "reference", bytes("bosTrigger")).body()).isEqualTo(reference);
        assertThatThrownBy(() -> captures.trigger(owner, sid, "disturbed", "fresh", bytes("disturbedTrigger")))
                .hasMessage("RECOVERY_REQUIRED");
        captures.cancel(owner, sid, "cancel");
        captures.cancel(owner, sid, "cancel");
        assertThat(count.get()).isOne();
        assertThat(captures.status(owner, sid).get("state").toString()).isEqualTo("CANCELLED");
        Files.write(media.getOriginalFrame(reference.asset().assetId()).path(), new byte[]{1,2,3});
        captures.close();
        start();
        assertThatThrownBy(() -> captures.trigger(owner, sid, "reference", "reference", bytes("bosTrigger")))
                .hasMessage("RECOVERY_REQUIRED");
        assertThatThrownBy(() -> captures.asset(owner, sid, reference.asset().assetId())).hasMessage("ASSET_NOT_FOUND");
    }

    @Test void malformedPlansAndCancellationBeforeDispatchNeverTouchShutter() throws Exception {
        String owner = exchange.pairingId();
        for (String bad : List.of("unknownPlanField", "nonContiguousPlan", "duplicateStepIdPlan", "missingDisplayHashPlan")) {
            assertThatThrownBy(() -> captures.create(owner, "create", plan(bad))).isInstanceOf(FresnelProblem.class);
        }
        var badScalar = (ObjectNode) FresnelPlan.JSON.readTree(plan("bosPlan"));
        badScalar.put("externalExperimentId", 42);
        assertThatThrownBy(() -> captures.create(owner, "numeric-id", FresnelPlan.JSON.writeValueAsBytes(badScalar)))
                .hasMessage("INVALID_REQUEST");
        var locked = (ObjectNode) FresnelPlan.JSON.readTree(plan("bosPlan"));
        ((ObjectNode) locked.get("requestedSettings")).put("lockFocus", true);
        assertThatThrownBy(() -> captures.create(owner, "locked", FresnelPlan.JSON.writeValueAsBytes(locked)))
                .hasMessage("UNSUPPORTED_SETTINGS");
        verifyNoInteractions(backends);
        var slow = (ObjectNode) FresnelPlan.JSON.readTree(plan("bosPlan"));
        ((ObjectNode)slow.get("steps").get(0)).put("settleDelayMs", 30_000);
        String sid = (String)captures.create(owner, "slow", FresnelPlan.JSON.writeValueAsBytes(slow)).get("sessionId");
        captures.trigger(owner, sid, "reference", "reserve", bytes("bosTrigger"));
        captures.close();
        start();
        assertThatThrownBy(() -> captures.trigger(owner, sid, "reference", "reserve", bytes("bosTrigger"))).hasMessage("RECOVERY_REQUIRED");
        captures.cancel(owner, sid, "stop");
        assertThat(captures.status(owner, sid).get("steps").toString()).contains("CANCELLED");
        assertThat(count.get()).isZero();
        assertThat(leases.metrics().activeLeases()).isZero();
    }

    @Test void displayRequiresThePresentedPatternAndTimeoutReleasesItsLease() throws Exception {
        String owner = exchange.pairingId();
        String sid = (String) captures.create(owner, "display", plan("displayPlan")).get("sessionId");
        assertThatThrownBy(() -> captures.trigger(owner, sid, "light-red", "red", bytes("wrongDisplayHash")))
                .hasMessage("PATTERN_MISMATCH");
        var red = complete(owner, sid, "light-red", bytes("displayTrigger"));
        assertThat(red.asset().role()).isEqualTo("DIRECTIONAL_LIGHT");
        captures.expire(Instant.now().plusSeconds(61));
        assertThat(captures.status(owner, sid).get("state").toString()).isEqualTo("PAUSED");
        assertThat(leases.metrics().activeLeases()).isZero();
        captures.cancel(owner, sid, "stop");
        assertThatThrownBy(() -> captures.cancel(owner, sid, "stop", "{}".getBytes())).hasMessage("IDEMPOTENCY_CONFLICT");
        assertThat(count.get()).isOne();
    }

    @Test void retentionKeepsReplayForAtLeastOneDayThenRemovesTheSourceAndJournal() throws Exception {
        captures.close();
        captures = new FresnelCaptures(profiles, backends, identities, leases, pairings, media, metrics, directory, Duration.ofDays(1));
        String owner = exchange.pairingId();
        String sid = (String) captures.create(owner, "expire", plan("bosPlan")).get("sessionId");
        var first = complete(owner, sid, "reference", bytes("bosTrigger"));
        captures.cancel(owner, sid, "stop");
        captures.expire(Instant.now().plusSeconds(3600));
        assertThat(captures.asset(owner, sid, first.asset().assetId())).isNotNull();
        captures.expire(Instant.now().plus(Duration.ofDays(2)));
        assertThatThrownBy(() -> captures.status(owner, sid)).hasMessage("SESSION_NOT_FOUND");
        assertThat(media.getOriginalFrame(first.asset().assetId())).isNull();
        assertThat(directory.resolve("sessions").resolve(sid + ".json")).doesNotExist();
    }

    @Test void competingCameraOwnerIsVisibleAndRejectedBeforeBackendContact() throws Exception {
        try (var other = leases.acquire(new CameraLeaseRequest(identities.resolve(profile).orElseThrow(),
                CameraWorkflowType.DIRECT_CONTROL, "owner", "owner", Duration.ofMinutes(1)))) {
            assertThat(captures.cameras().getFirst().get("state")).isEqualTo("BUSY");
            assertThatThrownBy(() -> captures.create(exchange.pairingId(), "contested", plan("bosPlan")))
                    .hasMessage("LEASE_CONFLICT");
            verifyNoInteractions(backends);
        }
        assertThat(captures.cameras().getFirst().get("state")).isEqualTo("CONNECTED");
    }

    @Test void restartFinishesInterruptedPurgeBeforeServingTheExpiredRecord() throws Exception {
        String owner = exchange.pairingId();
        String sid = (String) captures.create(owner, "expire", plan("bosPlan")).get("sessionId");
        var first = complete(owner, sid, "reference", bytes("bosTrigger"));
        captures.cancel(owner, sid, "stop");
        captures.close();
        var path = directory.resolve("sessions").resolve(sid + ".json");
        var stored = FresnelPlan.JSON.readValue(path.toFile(), CaptureSession.class);
        stored.purging = true;
        CaptureJournal.writeAtomically(path.getParent(), path.getFileName().toString(), FresnelPlan.JSON.writeValueAsBytes(stored));
        start();
        assertThatThrownBy(() -> captures.status(owner, sid)).hasMessage("SESSION_NOT_FOUND");
        assertThat(media.getOriginalFrame(first.asset().assetId())).isNull();
        assertThat(path).doesNotExist();
    }

    private CaptureSession.Result complete(String owner, String id, String step, byte[] body) throws Exception {
        assertThat(captures.trigger(owner, id, step, step, body).status()).isEqualTo(202);
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            var replay = captures.trigger(owner, id, step, step, body);
            if (replay.status() == 200) return (CaptureSession.Result) replay.body();
            Thread.sleep(10);
        }
        throw new AssertionError("Capture did not complete");
    }

    private byte[] plan(String name) throws Exception {
        ObjectNode node = fixtures.get(name).deepCopy();
        node.put("cameraProfileId", FresnelCaptures.alias(profile));
        return FresnelPlan.JSON.writeValueAsBytes(node);
    }

    private byte[] bytes(String key) throws Exception { return FresnelPlan.JSON.writeValueAsBytes(fixtures.get(key)); }

    private static byte[] responseBytes(StreamingResponseBody body) throws Exception {
        var out = new ByteArrayOutputStream();
        body.writeTo(out);
        return out.toByteArray();
    }
}
