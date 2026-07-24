import { API_BASE_URL, request } from './auth';

export function getAllJobs() {
  return request('/jobs');
}

export function getJob(id) {
  return request(`/jobs/${id}`);
}

export function createJob(data) {
  return request('/jobs', {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

export function startJob(id) {
  return request(`/jobs/${id}/start`, { method: 'POST' });
}

export function pauseJob(id) {
  return request(`/jobs/${id}/pause`, { method: 'POST' });
}

export function cancelJob(id) {
  return request(`/jobs/${id}/cancel`, { method: 'POST' });
}

export function deleteJob(id) {
  return request(`/jobs/${id}`, { method: 'DELETE' });
}

/**
 * Export all frames for a job as a ZIP file.
 * @param {string} id - Job ID
 * @returns {Promise<Blob>} ZIP file blob
 */
export async function exportJobFrames(id) {
  const res = await fetch(`${API_BASE_URL}/jobs/${encodeURIComponent(id)}/export`, {
    credentials: 'include',
  });
  if (!res.ok) {
    const body = await res.text().catch(() => '');
    throw new Error(`${res.status} ${res.statusText}: ${body}`);
  }
  return res.blob();
}
