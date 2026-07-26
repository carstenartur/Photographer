import { request, requestBlob } from './auth';

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
 * Delete a terminal astro session and its files.
 * @param {string} id
 */
export function deleteAstroSession(id) {
  return request(`/astro/sessions/${encodeURIComponent(id)}`, { method: 'DELETE' });
}

/**
 * Get result for a completed astro session.
 * @param {string} id
 */
export function getAstroResult(id) {
  return request(`/astro/sessions/${encodeURIComponent(id)}/result`);
}

/**
 * Download the stacked result image for a completed astro session.
 * @param {string} id
 * @returns {Promise<Blob>} image blob
 */
export function downloadAstroResultImage(id) {
  return requestBlob(`/astro/sessions/${encodeURIComponent(id)}/result`);
}
