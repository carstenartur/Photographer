const BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080/api';

async function request(path, options = {}) {
  const res = await fetch(`${BASE_URL}${path}`, {
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
