package com.photographer.focusstack.ai.permission;

import com.photographer.focusstack.ai.VisionContext;
import com.photographer.focusstack.ai.VisionProvider;
import com.photographer.focusstack.ai.VisionResult;
import com.photographer.focusstack.ai.ProviderCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Security gateway that sits in front of a {@link VisionProvider} and enforces
 * the configured {@link AIPermission} policy at runtime.
 *
 * <p>Every AI operation that would transmit image data is checked before
 * forwarding to the underlying provider. If the configured permission does not
 * allow the operation, an error {@link VisionResult} is returned immediately and
 * the underlying provider is never called.</p>
 *
 * <p>Thread safety: as thread-safe as the wrapped {@link VisionProvider}.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * VisionProvider provider = new GeminiVisionProvider(config);
 * AIPermission permission = new AIPermission(AIAccessLevel.READ_ONLY, AIPrivacyScope.THUMBNAILS);
 * AIGateway gateway = new AIGateway(provider, permission);
 *
 * // Wrapping the provider ensures the gateway is the only access point.
 * VisionResult result = gateway.classifyImage(imageBytes, VisionContext.wildlife());
 * }</pre>
 */
public class AIGateway implements VisionProvider {

    private static final Logger log = LoggerFactory.getLogger(AIGateway.class);

    private final VisionProvider delegate;
    private volatile AIPermission permission;

    /**
     * Creates a gateway wrapping {@code delegate} with the given initial permission.
     *
     * @param delegate   the underlying provider; must not be {@code null}
     * @param permission the initial permission policy; must not be {@code null}
     */
    public AIGateway(VisionProvider delegate, AIPermission permission) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.permission = Objects.requireNonNull(permission, "permission must not be null");
    }

    /**
     * Updates the permission policy at runtime.
     *
     * <p>The change takes effect for all subsequent calls. In-flight calls
     * are not affected.</p>
     *
     * @param permission the new permission; must not be {@code null}
     */
    public void setPermission(AIPermission permission) {
        this.permission = Objects.requireNonNull(permission, "permission must not be null");
        log.info("AIGateway permission updated: accessLevel={} privacyScope={}",
                permission.accessLevel(), permission.privacyScope());
    }

    /** Returns the currently configured permission. */
    public AIPermission getPermission() {
        return permission;
    }

    @Override
    public String name() {
        return "gateway[" + delegate.name() + "]";
    }

    /**
     * Classifies an image, subject to {@link AIAccessLevel#READ_ONLY} and
     * {@link AIPrivacyScope#FULL_IMAGES} requirements.
     */
    @Override
    public VisionResult classifyImage(byte[] imageData, VisionContext context) {
        if (!checkPermission(AIAccessLevel.READ_ONLY, AIPrivacyScope.FULL_IMAGES, "classifyImage")) {
            return denied("classifyImage");
        }
        return delegate.classifyImage(imageData, context);
    }

    /**
     * Describes an image, subject to {@link AIAccessLevel#READ_ONLY} and
     * {@link AIPrivacyScope#FULL_IMAGES} requirements.
     */
    @Override
    public VisionResult describeImage(byte[] imageData, VisionContext context) {
        if (!checkPermission(AIAccessLevel.READ_ONLY, AIPrivacyScope.FULL_IMAGES, "describeImage")) {
            return denied("describeImage");
        }
        return delegate.describeImage(imageData, context);
    }

    /**
     * Detects objects, subject to {@link AIAccessLevel#READ_ONLY} and
     * {@link AIPrivacyScope#FULL_IMAGES} requirements.
     */
    @Override
    public VisionResult detectObjects(byte[] imageData, VisionContext context) {
        if (!checkPermission(AIAccessLevel.READ_ONLY, AIPrivacyScope.FULL_IMAGES, "detectObjects")) {
            return denied("detectObjects");
        }
        return delegate.detectObjects(imageData, context);
    }

    /**
     * Ranks multiple images, subject to {@link AIAccessLevel#READ_ONLY} and
     * {@link AIPrivacyScope#FULL_IMAGES} requirements.
     */
    @Override
    public VisionResult rankBestImage(List<byte[]> images, VisionContext context) {
        if (!checkPermission(AIAccessLevel.READ_ONLY, AIPrivacyScope.FULL_IMAGES, "rankBestImage")) {
            return denied("rankBestImage");
        }
        return delegate.rankBestImage(images, context);
    }

    /**
     * Returns {@code true} only when the delegate is available <em>and</em>
     * the access level is at least {@link AIAccessLevel#READ_ONLY}.
     */
    @Override
    public boolean isAvailable() {
        return permission.accessLevel().allows(AIAccessLevel.READ_ONLY)
                && delegate.isAvailable();
    }

    @Override
    public ProviderCapabilities capabilities() {
        return delegate.capabilities();
    }

    // ---- internal helpers ----

    private boolean checkPermission(AIAccessLevel requiredLevel, AIPrivacyScope requiredScope,
                                    String operation) {
        if (!permission.allows(requiredLevel, requiredScope)) {
            log.warn("AIGateway blocked operation '{}': required=({},{}) current=({},{})",
                    operation, requiredLevel, requiredScope,
                    permission.accessLevel(), permission.privacyScope());
            return false;
        }
        return true;
    }

    private VisionResult denied(String operation) {
        return VisionResult.error(delegate.name(), "gateway", "Permission denied for: " + operation);
    }
}
