package com.photographer.focusstack.ai.permission;

/**
 * Combines an {@link AIAccessLevel} with an {@link AIPrivacyScope} to form a
 * complete AI permission policy for a user or job.
 *
 * <p>Both dimensions must be satisfied for an AI operation to be permitted:
 * the access level controls <em>what actions</em> are allowed, and the privacy
 * scope controls <em>what data</em> may be transmitted.</p>
 *
 * @param accessLevel  the permitted action level; must not be {@code null}
 * @param privacyScope the permitted data scope; must not be {@code null}
 */
public record AIPermission(
        AIAccessLevel accessLevel,
        AIPrivacyScope privacyScope
) {

    /**
     * A safe default permission: AI is completely disabled.
     * Use this as the starting point and explicitly elevate when needed.
     */
    public static final AIPermission DISABLED =
            new AIPermission(AIAccessLevel.DISABLED, AIPrivacyScope.METADATA_ONLY);

    /**
     * Read-only permission with full-image access — suitable for offline
     * analysis workflows where the user has explicitly opted in.
     */
    public static final AIPermission READ_ONLY_FULL =
            new AIPermission(AIAccessLevel.READ_ONLY, AIPrivacyScope.FULL_IMAGES);

    /**
     * Fully autonomous permission with full-image access.
     * Only for controlled, non-critical environments.
     */
    public static final AIPermission AUTONOMOUS_FULL =
            new AIPermission(AIAccessLevel.AUTONOMOUS, AIPrivacyScope.FULL_IMAGES);

    /**
     * Returns {@code true} when this permission satisfies both the required
     * access level and the required privacy scope.
     *
     * @param requiredLevel the minimum required access level
     * @param requiredScope the minimum required privacy scope
     * @return {@code true} iff both dimensions are satisfied
     */
    public boolean allows(AIAccessLevel requiredLevel, AIPrivacyScope requiredScope) {
        return accessLevel.allows(requiredLevel) && privacyScope.allows(requiredScope);
    }
}
