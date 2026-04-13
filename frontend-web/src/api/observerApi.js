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
export function getEvent(id) {
  return request(`/observer/events/${encodeURIComponent(id)}`);
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
