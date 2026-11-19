import { request, requestBlob } from './auth';

/**
 * Fetch recent observation events.
 * @param {number} limit - Maximum number of events (default 20, max 100)
 */
export function getRecentEvents(limit = 20) {
  return request(`/observer/events?limit=${limit}`);
}

/**
 * Fetch a single observation event by ID.
 * @param {string} id - Event UUID
 */
export function getEvent(id, signal) {
  return request(`/observer/events/${encodeURIComponent(id)}`, { signal });
}

export function getEventPreview(id, signal) {
  return requestBlob(`/observer/events/${encodeURIComponent(id)}/image`, { signal });
}

export function getEventOriginal(id, signal) {
  return requestBlob(`/observer/events/${encodeURIComponent(id)}/original`, { signal });
}

/**
 * Fetch events filtered by mode.
 * @param {string} mode - 'wildlife' or 'macro_pollinator'
 * @param {number} limit - Maximum number of events
 */
export function getEventsByMode(mode, limit = 50) {
  return request(`/observer/events/by-mode?mode=${encodeURIComponent(mode)}&limit=${limit}`);
}

/**
 * Fetch events for a specific date.
 * @param {string} date - ISO date string (yyyy-MM-dd)
 */
export function getEventsByDate(date) {
  const params = date ? `?date=${encodeURIComponent(date)}` : '';
  return request(`/observer/events/by-date${params}`);
}

/**
 * Fetch species ranking sorted by sightings count.
 */
export function getSpeciesRanking() {
  return request('/observer/species');
}

/**
 * Fetch summary statistics.
 */
export function getStats() {
  return request('/observer/stats');
}

/**
 * Fetch current observer status.
 */
export function getStatus() {
  return request('/observer/status');
}

export function getLiveObserverStatus(signal) {
  return request('/observer/live', { signal, cache: 'no-store' });
}

export function startLiveObserver(config, signal) {
  return request('/observer/live/start', {
    method: 'POST', body: JSON.stringify(config), signal,
  });
}

export function updateLiveObserverSettings(config, signal) {
  return request('/observer/live/settings', {
    method: 'PUT', body: JSON.stringify(config), signal,
  });
}

export function stopLiveObserver(signal) {
  return request('/observer/live/stop', { method: 'POST', body: '{}', signal });
}

/**
 * Fetch available observer profiles.
 */
export function getProfiles() {
  return request('/observer/profiles');
}

/**
 * Fetch a single observer profile by name.
 * @param {string} name - Profile name (e.g. 'birdhouse')
 */
export function getProfile(name) {
  return request(`/observer/profiles/${encodeURIComponent(name)}`);
}

/**
 * Delete a single observation event.
 * @param {string} id - Event UUID
 */
export function deleteEvent(id) {
  return request(`/observer/events/${encodeURIComponent(id)}`, { method: 'DELETE' });
}

/**
 * Fetch daily event counts for the last N days.
 * @param {number} days - Number of days (default 7, max 90)
 */
export function getDailyStats(days = 7) {
  return request(`/observer/stats/daily?days=${days}`);
}

/**
 * Fetch detailed statistics for a specific species.
 * @param {string} name - Species name
 */
export function getSpeciesDetail(name) {
  return request(`/observer/stats/species/${encodeURIComponent(name)}`);
}

/**
 * Update observer runtime configuration.
 * @param {Object} config - Config fields to update (defaultProfile, preferredAiProvider, enabled)
 */
export function updateConfig(config) {
  return request('/observer/config', {
    method: 'PUT',
    body: JSON.stringify(config),
  });
}

/**
 * Fetch available AI providers with their status.
 */
export function getAiProviders() {
  return request('/observer/ai/providers');
}

/**
 * Delete a species record by numeric ID.
 * @param {number} id - Species record ID
 */
export function deleteSpecies(id) {
  return request(`/observer/species/${encodeURIComponent(id)}`, { method: 'DELETE' });
}

/**
 * Create a new observer profile.
 * @param {Object} profile - Profile data
 */
export function createProfile(profile) {
  return request('/observer/profiles', {
    method: 'POST',
    body: JSON.stringify(profile),
  });
}

/**
 * Update an existing observer profile.
 * @param {string} name - Profile name
 * @param {Object} profile - Updated profile data
 */
export function updateProfile(name, profile) {
  return request(`/observer/profiles/${encodeURIComponent(name)}`, {
    method: 'PUT',
    body: JSON.stringify(profile),
  });
}

/**
 * Test an AI provider connection.
 * @param {Object} testRequest - { provider: string }
 */
export function testAiProvider(testRequest) {
  return request('/observer/ai/test', {
    method: 'POST',
    body: JSON.stringify(testRequest),
  });
}

/**
 * Export observer events as CSV or JSON.
 * @param {string} format - 'csv' or 'json'
 * @returns {Promise<Blob>} file content
 */
export function exportEvents(format = 'json') {
  return requestBlob(`/observer/export/events?format=${encodeURIComponent(format)}`);
}

/**
 * Export species records as CSV or JSON.
 * @param {string} format - 'csv' or 'json'
 * @returns {Promise<Blob>} file content
 */
export function exportSpecies(format = 'json') {
  return requestBlob(`/observer/export/species?format=${encodeURIComponent(format)}`);
}
