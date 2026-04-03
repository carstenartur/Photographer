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

export function getAllCameras() {
  return request('/cameras');
}

export function getCamera(id) {
  return request(`/cameras/${id}`);
}

export function createCamera(data) {
  return request('/cameras', {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

export function updateCamera(id, data) {
  return request(`/cameras/${id}`, {
    method: 'PUT',
    body: JSON.stringify(data),
  });
}

export function deleteCamera(id) {
  return request(`/cameras/${id}`, { method: 'DELETE' });
}

export function getCameraStatus(id) {
  return request(`/cameras/${id}/status`);
}

export function getLiveView(id) {
  return request(`/cameras/${id}/liveview`);
}

export function triggerCapture(id) {
  return request(`/cameras/${id}/capture`, { method: 'POST' });
}

export function adjustFocus(id, direction, steps) {
  return request(`/cameras/${id}/focus`, {
    method: 'POST',
    body: JSON.stringify({ direction, steps }),
  });
}
