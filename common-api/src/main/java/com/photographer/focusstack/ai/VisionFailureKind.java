package com.photographer.focusstack.ai;

/** Provider-neutral reason why an AI vision operation did not produce a result. */
public enum VisionFailureKind {
    /** The operation succeeded; no failure is present. */
    NONE,

    /** Required provider configuration such as credentials is absent. */
    NOT_CONFIGURED,

    /** A local privacy or permission policy blocked transmission before provider contact. */
    POLICY_DENIED,

    /** Credentials were rejected or permission is insufficient. */
    AUTHENTICATION,

    /** The provider rejected the submitted request or model configuration. */
    INVALID_REQUEST,

    /** The request exceeded its bounded deadline. */
    TIMEOUT,

    /** The provider explicitly rate-limited the caller. */
    RATE_LIMITED,

    /** The provider or its upstream service is temporarily unavailable. */
    TEMPORARY_UNAVAILABLE,

    /** A provider response could not be interpreted according to the contract. */
    MALFORMED_RESPONSE,

    /** The calling thread was interrupted; callers must preserve interruption. */
    INTERRUPTED,

    /** A circuit breaker rejected the call before contacting the provider. */
    CIRCUIT_OPEN,

    /** A non-transient provider-side error has no more specific category. */
    PERMANENT_PROVIDER_ERROR,

    /** Compatibility category for an older or unclassified error result. */
    UNKNOWN
}
