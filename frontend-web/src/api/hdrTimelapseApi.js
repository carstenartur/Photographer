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

export function createHdrSession(settings) {
  return request('/hdr-timelapse', {
    method: 'POST',
    body: JSON.stringify(settings),
  });
}

export function getHdrSession(sessionId) {
  return request(`/hdr-timelapse/${sessionId}`);
}

export function startHdrSession(sessionId) {
  return request(`/hdr-timelapse/${sessionId}/start`, { method: 'POST' });
}

export function stopHdrSession(sessionId) {
  return request(`/hdr-timelapse/${sessionId}/stop`, { method: 'POST' });
}

export function getHdrBrackets(sessionId) {
  return request(`/hdr-timelapse/${sessionId}/brackets`);
}

export async function generateHdrVideo(sessionId, fps = 24, width = 1920, height = 1080) {
  const res = await fetch(
    `${BASE_URL}/hdr-timelapse/${sessionId}/video?fps=${fps}&width=${width}&height=${height}`,
    {
      method: 'POST',
      credentials: 'include',
    }
  );
  if (res.status === 204) return null;
  if (!res.ok) {
    const body = await res.text().catch(() => '');
    throw new Error(`${res.status} ${res.statusText}: ${body}`);
  }
  return res.blob();
}
