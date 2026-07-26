package com.photographer.focusstack.app.api;

/**
 * Version-one real-time transport boundary.
 *
 * <p>The established endpoints remain unprefixed for client compatibility.
 * Their payload contract is v1. A breaking event-envelope change requires a
 * new endpoint/topic namespace rather than silently changing these constants.</p>
 */
public final class RealtimeApiPaths {

    public static final int SCHEMA_VERSION = 1;
    public static final String STOMP_ENDPOINT = "/ws";
    public static final String PLAIN_ENDPOINT = "/ws/plain";
    public static final String BROKER_PREFIX = "/topic";
    public static final String APPLICATION_PREFIX = "/app";

    private RealtimeApiPaths() {
    }
}
