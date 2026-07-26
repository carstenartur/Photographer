package com.photographer.focusstack.ai.permission;

import com.photographer.focusstack.ai.VisionContext;
import com.photographer.focusstack.ai.VisionFailureKind;
import com.photographer.focusstack.ai.VisionResult;
import com.photographer.focusstack.ai.mock.MockVisionProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AIGatewayTest {

    private static final byte[] IMAGE = new byte[]{1, 2, 3};

    private static MockVisionProvider delegate(String label) {
        return new MockVisionProvider(new VisionResult(
                "mock", "m", "classification", label,
                List.of(), 0.9, null, List.of(), null,
                5L, true, null));
    }

    @Test
    void disabledClassificationReturnsTypedPolicyDenial() {
        MockVisionProvider delegate = delegate("Vogel");
        AIGateway gateway = new AIGateway(delegate, AIPermission.DISABLED);

        VisionResult result = gateway.classifyImage(
                IMAGE, VisionContext.wildlife());

        assertPolicyDenied(result, "classification");
        assertEquals(gateway.name(), result.provider());
        assertEquals("local-policy", result.model());
        assertEquals(0, delegate.getCallCount());
    }

    @Test
    void deniedOperationsKeepTaskSpecificShapeWithoutCallingDelegate() {
        MockVisionProvider delegate = delegate("Vogel");
        AIGateway gateway = new AIGateway(delegate, AIPermission.DISABLED);

        assertPolicyDenied(
                gateway.describeImage(IMAGE, VisionContext.wildlife()),
                "description");
        assertPolicyDenied(
                gateway.detectObjects(IMAGE, VisionContext.wildlife()),
                "object_detection");
        assertPolicyDenied(
                gateway.rankBestImage(List.of(IMAGE), VisionContext.wildlife()),
                "ranking");
        assertEquals(0, delegate.getCallCount());
    }

    @Test
    void disabledIsUnavailable() {
        AIGateway gateway = new AIGateway(
                delegate("Vogel"), AIPermission.DISABLED);

        assertFalse(gateway.isAvailable());
    }

    @Test
    void readOnlyFullDelegatesClassification() {
        MockVisionProvider delegate = delegate("Kohlmeise");
        AIGateway gateway = new AIGateway(
                delegate, AIPermission.READ_ONLY_FULL);

        VisionResult result = gateway.classifyImage(
                IMAGE, VisionContext.wildlife());

        assertTrue(result.successful());
        assertEquals(VisionFailureKind.NONE, result.failureKind());
        assertEquals("Kohlmeise", result.primaryLabel());
        assertEquals(1, delegate.getCallCount());
    }

    @Test
    void readOnlyFullIsAvailableWhenDelegateIsAvailable() {
        AIGateway gateway = new AIGateway(
                delegate("x"), AIPermission.READ_ONLY_FULL);

        assertTrue(gateway.isAvailable());
    }

    @Test
    void metadataOnlyScopeReturnsPolicyDenial() {
        AIPermission metadataOnly = new AIPermission(
                AIAccessLevel.READ_ONLY,
                AIPrivacyScope.METADATA_ONLY);
        MockVisionProvider delegate = delegate("Vogel");
        AIGateway gateway = new AIGateway(delegate, metadataOnly);

        VisionResult result = gateway.classifyImage(
                IMAGE, VisionContext.wildlife());

        assertPolicyDenied(result, "classification");
        assertEquals(0, delegate.getCallCount());
    }

    @Test
    void setPermissionUpdatesEnforcedPolicy() {
        MockVisionProvider delegate = delegate("Vogel");
        AIGateway gateway = new AIGateway(delegate, AIPermission.DISABLED);

        gateway.classifyImage(IMAGE, VisionContext.wildlife());
        assertEquals(0, delegate.getCallCount());

        gateway.setPermission(AIPermission.READ_ONLY_FULL);
        gateway.classifyImage(IMAGE, VisionContext.wildlife());
        assertEquals(1, delegate.getCallCount());
    }

    @Test
    void getPermissionReturnsCurrentPermission() {
        AIGateway gateway = new AIGateway(
                delegate("x"), AIPermission.DISABLED);
        assertEquals(AIPermission.DISABLED, gateway.getPermission());

        gateway.setPermission(AIPermission.READ_ONLY_FULL);
        assertEquals(AIPermission.READ_ONLY_FULL, gateway.getPermission());
    }

    @Test
    void nameWrapsDelegate() {
        AIGateway gateway = new AIGateway(
                delegate("x"), AIPermission.DISABLED);

        assertTrue(gateway.name().contains("mock"));
    }

    @Test
    void constructorAndSetterRejectNulls() {
        assertThrows(NullPointerException.class,
                () -> new AIGateway(null, AIPermission.DISABLED));
        assertThrows(NullPointerException.class,
                () -> new AIGateway(delegate("x"), null));

        AIGateway gateway = new AIGateway(
                delegate("x"), AIPermission.DISABLED);
        assertThrows(NullPointerException.class,
                () -> gateway.setPermission(null));
    }

    private static void assertPolicyDenied(
            VisionResult result,
            String taskType) {
        assertFalse(result.successful());
        assertEquals(taskType, result.taskType());
        assertEquals("unclassified", result.primaryLabel());
        assertEquals(VisionFailureKind.POLICY_DENIED, result.failureKind());
        assertFalse(result.retryable());
        assertNull(result.retryAfterSeconds());
        assertNull(result.rawResponse());
        assertTrue(result.errorMessage().contains("policy denied"));
    }
}
