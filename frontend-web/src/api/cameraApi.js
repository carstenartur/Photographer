import { request } from './auth';

export function getAllCameras(options) {
  return request('/cameras', options);
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

export function getCameraInfo(id) {
  return request(`/cameras/${id}/info`);
}

export function getLiveView(id) {
  return request(`/cameras/${id}/liveview`);
}

/**
 * Fetch the liveview frame and return it as an object URL for an <img> src.
 * The /liveview endpoint returns JSON with a base64-encoded `data` field.
 */
export async function getLiveViewImage(id) {
  const frame = await request(`/cameras/${id}/liveview`);
  if (!frame || !frame.data) return null;
  const contentType = frame.contentType || 'image/png';
  return `data:${contentType};base64,${frame.data}`;
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
