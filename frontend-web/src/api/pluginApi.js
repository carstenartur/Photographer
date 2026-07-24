import { authenticatedFetch, request } from './auth';

/**
 * List all installed plugins.
 */
export function listPlugins() {
  return request('/admin/plugins');
}

/**
 * Get details for a single plugin.
 * @param {string} id - Plugin ID
 */
export function getPlugin(id) {
  return request(`/admin/plugins/${encodeURIComponent(id)}`);
}

/**
 * Install a plugin from a JAR file.
 * @param {File} file - The plugin JAR file
 */
export async function installPlugin(file) {
  const formData = new FormData();
  formData.append('file', file);
  const res = await authenticatedFetch('/admin/plugins/install', {
    method: 'POST',
    body: formData,
  });
  if (!res.ok) {
    const body = await res.text().catch(() => '');
    throw new Error(`${res.status} ${res.statusText}: ${body}`);
  }
  return res.json();
}

/**
 * Enable a plugin.
 * @param {string} id - Plugin ID
 */
export function enablePlugin(id) {
  return request(`/admin/plugins/${encodeURIComponent(id)}/enable`, { method: 'POST' });
}

/**
 * Disable a plugin.
 * @param {string} id - Plugin ID
 */
export function disablePlugin(id) {
  return request(`/admin/plugins/${encodeURIComponent(id)}/disable`, { method: 'POST' });
}

/**
 * Uninstall a plugin.
 * @param {string} id - Plugin ID
 */
export function uninstallPlugin(id) {
  return request(`/admin/plugins/${encodeURIComponent(id)}`, { method: 'DELETE' });
}
