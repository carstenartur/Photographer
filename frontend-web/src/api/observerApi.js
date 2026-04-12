const BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080/api';

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
