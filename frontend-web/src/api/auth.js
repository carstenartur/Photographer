/**
 * Centralised auth store and authenticated fetch helper.
 *
 * Credentials are kept in memory (cleared on page reload) and sent as
 * HTTP Basic Authorization headers on every API request.
 */

let _credentials = null; // { username, password }

export function setCredentials(username, password) {
  _credentials = { username, password };
}

export function clearCredentials() {
  _credentials = null;
}

export function isAuthenticated() {
  return _credentials !== null;
}

/**
 * Build the Authorization header value for the current credentials.
 * Returns undefined when no credentials are stored.
 */
function authHeader() {
  if (!_credentials) return undefined;
  return 'Basic ' + btoa(`${_credentials.username}:${_credentials.password}`);
}

/** Canonical v1 REST base. Deployments can override the complete prefix. */
export const API_BASE_URL = import.meta.env.VITE_API_URL || '/api/v1';

/**
 * Authenticated fetch wrapper used by all API modules.
 *
 * @param {string} path  – relative to /api/v1, e.g. '/cameras'
 * @param {RequestInit} options – standard fetch options
 */
export async function request(path, options = {}) {
  const auth = authHeader();
  const headers = {
    'Content-Type': 'application/json',
    ...options.headers,
  };
  if (auth) {
    headers['Authorization'] = auth;
  }
  const res = await fetch(`${API_BASE_URL}${path}`, {
    credentials: 'include',
    ...options,
    headers,
  });
  if (res.status === 401) {
    // Clear stale credentials so the login page is shown again
    clearCredentials();
    // Dispatch a custom event so the AuthContext can react
    window.dispatchEvent(new Event('auth:unauthorized'));
    throw new Error('Authentication required');
  }
  if (!res.ok) {
    const body = await res.text().catch(() => '');
    throw new Error(`${res.status} ${res.statusText}: ${body}`);
  }
  if (res.status === 204) return null;
  return res.json();
}
