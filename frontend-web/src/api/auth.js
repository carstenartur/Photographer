/**
 * Centralised auth store and authenticated transport helpers.
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

/** Build a URL below the canonical REST base without duplicating separators. */
export function apiUrl(path) {
  const base = API_BASE_URL.replace(/\/$/, '');
  const suffix = path.startsWith('/') ? path : `/${path}`;
  return `${base}${suffix}`;
}

/**
 * Build a WebSocket URL for the independently versioned v1 event transport.
 * REST path segments are deliberately not copied into the WebSocket endpoint.
 */
export function webSocketUrl(path = '/ws/plain') {
  const explicitBase = import.meta.env.VITE_WS_URL;
  if (explicitBase) {
    return `${explicitBase.replace(/\/$/, '')}${path.startsWith('/') ? path : `/${path}`}`;
  }

  const apiLocation = new URL(
    import.meta.env.VITE_API_URL || window.location.origin,
    window.location.origin,
  );
  apiLocation.protocol = apiLocation.protocol === 'https:' ? 'wss:' : 'ws:';
  apiLocation.pathname = path.startsWith('/') ? path : `/${path}`;
  apiLocation.search = '';
  apiLocation.hash = '';
  return apiLocation.toString();
}

/**
 * Authenticated fetch wrapper for JSON, multipart and binary requests.
 *
 * @param {string} path – relative to /api/v1, e.g. '/cameras'
 * @param {RequestInit} options – standard fetch options
 */
export async function authenticatedFetch(path, options = {}) {
  const headers = new Headers(options.headers || {});
  const auth = authHeader();
  if (auth && !headers.has('Authorization')) {
    headers.set('Authorization', auth);
  }

  const res = await fetch(apiUrl(path), {
    credentials: 'include',
    ...options,
    headers,
  });
  if (res.status === 401) {
    clearCredentials();
    window.dispatchEvent(new Event('auth:unauthorized'));
    throw new Error('Authentication required');
  }
  return res;
}

async function responseError(res) {
  const body = await res.text().catch(() => '');
  return new Error(`${res.status} ${res.statusText}: ${body}`);
}

/** Execute a JSON API request and decode its response. */
export async function request(path, options = {}) {
  const headers = new Headers(options.headers || {});
  if (!headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  const res = await authenticatedFetch(path, { ...options, headers });
  if (!res.ok) throw await responseError(res);
  if (res.status === 204) return null;
  return res.json();
}

/** Execute an API request whose successful response is binary. */
export async function requestBlob(path, options = {}) {
  const res = await authenticatedFetch(path, options);
  if (!res.ok) throw await responseError(res);
  if (res.status === 204) return null;
  return res.blob();
}
