const BASE_URL = import.meta.env.VITE_API_URL || '/api';

async function request(path, options = {}) {
  const res = await fetch(`${BASE_URL}${path}`, {
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', ...options.headers },
    ...options,
  });
  if (!res.ok) {
    const body = await res.text().catch(() => '');
    throw new Error(`${res.status} ${res.statusText}: ${body}`);
  }
  if (res.status === 204) return null;
  return res.json();
}

/**
 * Create a new astro session and start it.
 * @param {Object} config - { captureConfig, focusConfig }
 */
export function createAstroSession(config) {
  return request('/astro/sessions', {
    method: 'POST',
    body: JSON.stringify(config),
  });
}

/**
 * Get astro session by ID.
 * @param {string} id
 */
export function getAstroSession(id) {
  return request(`/astro/sessions/${encodeURIComponent(id)}`);
}

/**
 * List all astro sessions.
 */
export function listAstroSessions() {
  return request('/astro/sessions');
}

/**
 * Abort a running astro session.
 * @param {string} id
 */
export function abortAstroSession(id) {
  return request(`/astro/sessions/${encodeURIComponent(id)}/abort`, { method: 'POST' });
}

/**
 * Get result for a completed astro session.
 * @param {string} id
 */
export function getAstroResult(id) {
  return request(`/astro/sessions/${encodeURIComponent(id)}/result`);
}
