import { request, requestBlob } from './auth';

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

export function generateHdrVideo(sessionId, fps = 24, width = 1920, height = 1080) {
  const query = new URLSearchParams({ fps, width, height });
  return requestBlob(`/hdr-timelapse/${sessionId}/video?${query}`, {
    method: 'POST',
  });
}
