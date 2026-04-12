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

export function createFaceOrbitSession(settings) {
  return request('/face-orbit', {
    method: 'POST',
    body: JSON.stringify(settings),
  });
}

export function getFaceOrbitSession(sessionId) {
  return request(`/face-orbit/${sessionId}`);
}

export function captureNextStep(sessionId) {
  return request(`/face-orbit/${sessionId}/capture`, { method: 'POST' });
}

export function startAutoMode(sessionId) {
  return request(`/face-orbit/${sessionId}/auto`, { method: 'POST' });
}

export function stopFaceOrbitSession(sessionId) {
  return request(`/face-orbit/${sessionId}/stop`, { method: 'POST' });
}

export function completeFaceOrbitSession(sessionId) {
  return request(`/face-orbit/${sessionId}/complete`, { method: 'POST' });
}

export function getQualityChecks(sessionId) {
  return request(`/face-orbit/${sessionId}/quality`);
}

export function generateProfile(sessionId) {
  return request(`/face-orbit/${sessionId}/profile`, { method: 'POST' });
}

export function getProfileJob(jobId) {
  return request(`/face-orbit/profile/${jobId}`);
}
