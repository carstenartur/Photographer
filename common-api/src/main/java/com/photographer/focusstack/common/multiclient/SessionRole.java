package com.photographer.focusstack.common.multiclient;

/**
 * Role assigned to a client connected to a shared capture session.
 *
 * <p>Adapted from the "Companion" multi-client model in the sister project
 * <em>foto-fernsteuerung</em>: several browsers can attach to the same camera
 * session at the same time, but only one of them — the {@link #DIRECTOR} —
 * may transfer control or change another client's role. {@link #CONTROLLER}s
 * may issue capture / focus commands; {@link #OBSERVER}s receive live-view
 * updates and status events but cannot issue commands.</p>
 *
 * <p>The roles are <em>ordered</em> by privilege: see {@link #atLeast(SessionRole)}.</p>
 */
public enum SessionRole {

    /** Read-only attendee. Receives status / live-view; may not issue commands. */
    OBSERVER,

    /**
     * Active operator. May issue capture, focus, and other camera commands,
     * but may not change other clients' roles or transfer control.
     */
    CONTROLLER,

    /**
     * Session owner / admin. Has all {@link #CONTROLLER} privileges plus the
     * ability to grant or revoke roles, kick clients, and stop the session.
     */
    DIRECTOR;

    /** Returns {@code true} when this role is at or above {@code other}. */
    public boolean atLeast(SessionRole other) {
        return this.ordinal() >= other.ordinal();
    }

    /** May this role issue camera commands (capture, focus, settings)? */
    public boolean canControlCamera() {
        return atLeast(CONTROLLER);
    }

    /** May this role manage other clients (grant roles, kick, stop session)? */
    public boolean canManageSession() {
        return this == DIRECTOR;
    }
}
