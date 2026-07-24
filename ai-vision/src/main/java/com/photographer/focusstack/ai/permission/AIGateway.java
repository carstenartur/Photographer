package com.photographer.focusstack.ai.permission;

import com.photographer.focusstack.ai.ProviderCapabilities;
import com.photographer.focusstack.ai.VisionContext;
import com.photographer.focusstack.ai.VisionFailureKind;
import com.photographer.focusstack.ai.VisionProvider;
import com.photographer.focusstack.ai.VisionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Security gateway in front of a {@link VisionProvider}.
 *
 * <p>Denied operations return a typed local-policy failure and never contact
 * the provider. Provider authentication failures remain a separate category.</p>
 */
public class AIGateway implements VisionProvider {

    private static final Logger log = LoggerFactory.getLogger(AIGateway.class);

    private final VisionProvider delegate;
    private volatile AIPermission permission;

    public AIGateway(VisionProvider delegate, AIPermission permission) {
        this.delegate = Objects.requireNonNull(
                delegate, "delegate must not be null");
        this.permission = Objects.requireNonNull(
                permission, "permission must not be null");
    }

    public void setPermission(AIPermission permission) {
        this.permission = Objects.requireNonNull(
                permission, "permission must not be null");
        log.info("AI permission updated: accessLevel={} privacyScope={}",
                permission.accessLevel(), permission.privacyScope());
    }

    public AIPermission getPermission() {
        return permission;
    }

    @Override
    public String name() {
        return "gateway[" + delegate.name() + "]";
    }

    @Override
    public VisionResult classifyImage(
            byte[] imageData,
            VisionContext context) {
        if (!checkPermission(
                AIAccessLevel.READ_ONLY,
                AIPrivacyScope.FULL_IMAGES,
                "classifyImage")) {
            return denied("classification");
        }
        return delegate.classifyImage(imageData, context);
    }

    @Override
    public VisionResult describeImage(
            byte[] imageData,
            VisionContext context) {
        if (!checkPermission(
                AIAccessLevel.READ_ONLY,
                AIPrivacyScope.FULL_IMAGES,
                "describeImage")) {
            return denied("description");
        }
        return delegate.describeImage(imageData, context);
    }

    @Override
    public VisionResult detectObjects(
            byte[] imageData,
            VisionContext context) {
        if (!checkPermission(
                AIAccessLevel.READ_ONLY,
                AIPrivacyScope.FULL_IMAGES,
                "detectObjects")) {
            return denied("object_detection");
        }
        return delegate.detectObjects(imageData, context);
    }

    @Override
    public VisionResult rankBestImage(
            List<byte[]> images,
            VisionContext context) {
        if (!checkPermission(
                AIAccessLevel.READ_ONLY,
                AIPrivacyScope.FULL_IMAGES,
                "rankBestImage")) {
            return denied("ranking");
        }
        return delegate.rankBestImage(images, context);
    }

    @Override
    public boolean isAvailable() {
        return permission.accessLevel().allows(AIAccessLevel.READ_ONLY)
                && permission.privacyScope().allows(AIPrivacyScope.FULL_IMAGES)
                && delegate.isAvailable();
    }

    @Override
    public ProviderCapabilities capabilities() {
        return delegate.capabilities();
    }

    private boolean checkPermission(
            AIAccessLevel requiredLevel,
            AIPrivacyScope requiredScope,
            String operation) {
        if (!permission.allows(requiredLevel, requiredScope)) {
            log.warn("AI gateway blocked operation '{}': required=({},{}) current=({},{})",
                    operation,
                    requiredLevel,
                    requiredScope,
                    permission.accessLevel(),
                    permission.privacyScope());
            return false;
        }
        return true;
    }

    private VisionResult denied(String taskType) {
        return VisionResult.failure(
                name(),
                "local-policy",
                taskType,
                VisionFailureKind.POLICY_DENIED,
                false,
                null,
                "Local AI privacy or permission policy denied the operation",
                0L);
    }
}
