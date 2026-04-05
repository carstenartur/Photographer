package com.photographer.focusstack.app.security;

/**
 * User roles for the Focus Stack application.
 *
 * <ul>
 *   <li>{@link #ADMIN} — full access: camera configuration, user management, all API endpoints</li>
 *   <li>{@link #USER}  — operational access: view live-view, start/stop jobs, browse gallery</li>
 * </ul>
 */
public enum UserRole {
    ADMIN,
    USER
}
