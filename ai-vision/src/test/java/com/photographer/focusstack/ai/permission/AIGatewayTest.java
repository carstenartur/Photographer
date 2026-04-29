package com.photographer.focusstack.ai.permission;

import com.photographer.focusstack.ai.VisionContext;
import com.photographer.focusstack.ai.VisionResult;
import com.photographer.focusstack.ai.mock.MockVisionProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AIGatewayTest {

    private static final byte[] IMAGE = new byte[]{1, 2, 3};

    private MockVisionProvider makeDelegate(String label) {
        VisionResult r = new VisionResult("mock", "m", "classification",
                label, java.util.List.of(), 0.9, null, java.util.List.of(), null, 5L, true, null);
        return new MockVisionProvider(r);
    }

    // ---- DISABLED ----

    @Test
    void disabled_classifyImage_returnsDenied() {
        AIGateway gw = new AIGateway(makeDelegate("Vogel"), AIPermission.DISABLED);
        VisionResult result = gw.classifyImage(IMAGE, VisionContext.wildlife());
        assertFalse(result.successful());
        assertTrue(result.errorMessage().contains("Permission denied"));
    }

    @Test
    void disabled_isAvailable_returnsFalse() {
        AIGateway gw = new AIGateway(makeDelegate("Vogel"), AIPermission.DISABLED);
        assertFalse(gw.isAvailable());
    }

    @Test
    void disabled_delegate_neverCalled() {
        MockVisionProvider delegate = makeDelegate("Vogel");
        AIGateway gw = new AIGateway(delegate, AIPermission.DISABLED);
        gw.classifyImage(IMAGE, VisionContext.wildlife());
        assertEquals(0, delegate.getCallCount());
    }

    // ---- READ_ONLY / FULL_IMAGES ----

    @Test
    void readOnlyFull_classifyImage_delegateIsCalled() {
        MockVisionProvider delegate = makeDelegate("Kohlmeise");
        AIGateway gw = new AIGateway(delegate, AIPermission.READ_ONLY_FULL);
        VisionResult result = gw.classifyImage(IMAGE, VisionContext.wildlife());
        assertTrue(result.successful());
        assertEquals("Kohlmeise", result.primaryLabel());
        assertEquals(1, delegate.getCallCount());
    }

    @Test
    void readOnlyFull_isAvailable_trueWhenDelegateAvailable() {
        AIGateway gw = new AIGateway(makeDelegate("x"), AIPermission.READ_ONLY_FULL);
        assertTrue(gw.isAvailable());
    }

    // ---- Privacy scope blocks full images ----

    @Test
    void metadataOnlyScope_classifyImage_returnsDenied() {
        AIPermission metadataOnly = new AIPermission(AIAccessLevel.READ_ONLY, AIPrivacyScope.METADATA_ONLY);
        MockVisionProvider delegate = makeDelegate("Vogel");
        AIGateway gw = new AIGateway(delegate, metadataOnly);
        VisionResult result = gw.classifyImage(IMAGE, VisionContext.wildlife());
        assertFalse(result.successful());
        assertEquals(0, delegate.getCallCount());
    }

    // ---- setPermission updates at runtime ----

    @Test
    void setPermission_updatesEnforcedPolicy() {
        MockVisionProvider delegate = makeDelegate("Vogel");
        AIGateway gw = new AIGateway(delegate, AIPermission.DISABLED);

        // Initially blocked
        gw.classifyImage(IMAGE, VisionContext.wildlife());
        assertEquals(0, delegate.getCallCount());

        // Elevate permission
        gw.setPermission(AIPermission.READ_ONLY_FULL);
        gw.classifyImage(IMAGE, VisionContext.wildlife());
        assertEquals(1, delegate.getCallCount());
    }

    @Test
    void getPermission_returnsCurrentPermission() {
        AIGateway gw = new AIGateway(makeDelegate("x"), AIPermission.DISABLED);
        assertEquals(AIPermission.DISABLED, gw.getPermission());
        gw.setPermission(AIPermission.READ_ONLY_FULL);
        assertEquals(AIPermission.READ_ONLY_FULL, gw.getPermission());
    }

    // ---- name ----

    @Test
    void name_wrapsDelegate() {
        AIGateway gw = new AIGateway(makeDelegate("x"), AIPermission.DISABLED);
        assertTrue(gw.name().contains("mock"));
    }

    // ---- null safety ----

    @Test
    void constructor_nullDelegate_throws() {
        assertThrows(NullPointerException.class,
                () -> new AIGateway(null, AIPermission.DISABLED));
    }

    @Test
    void constructor_nullPermission_throws() {
        assertThrows(NullPointerException.class,
                () -> new AIGateway(makeDelegate("x"), null));
    }

    @Test
    void setPermission_null_throws() {
        AIGateway gw = new AIGateway(makeDelegate("x"), AIPermission.DISABLED);
        assertThrows(NullPointerException.class, () -> gw.setPermission(null));
    }
}
