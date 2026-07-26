package com.photographer.focusstack.app.api;

/**
 * Stable REST path prefixes.
 *
 * <p>{@link #V1} is the canonical public contract. {@link #LEGACY} remains a
 * compatibility alias during the documented v1 migration window. Controllers
 * must map both prefixes to the same handler methods so DTOs, errors, downloads
 * and authorization behavior cannot drift between aliases.</p>
 */
public final class ApiPaths {

    public static final String V1 = "/api/v1";
    public static final String LEGACY = "/api";

    private ApiPaths() {
    }
}
