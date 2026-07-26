import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  API_BASE_URL,
  apiUrl,
  clearCredentials,
  request,
  setCredentials,
  webSocketUrl,
} from './auth';

afterEach(() => {
  clearCredentials();
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
  vi.resetModules();
});

describe('versioned API transport', () => {
  it('uses /api/v1 as the canonical default', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('[]', {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }));
    vi.stubGlobal('fetch', fetchMock);

    await request('/cameras');

    expect(API_BASE_URL).toBe('/api/v1');
    expect(apiUrl('cameras')).toBe('/api/v1/cameras');
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/cameras', expect.any(Object));
  });

  it('normalizes a trailing slash in an explicit REST base', async () => {
    vi.stubEnv('VITE_API_URL', 'https://camera.example/api/v1/');
    vi.resetModules();

    const configuredTransport = await import('./auth');

    expect(configuredTransport.API_BASE_URL).toBe('https://camera.example/api/v1/');
    expect(configuredTransport.apiUrl('/cameras')).toBe(
      'https://camera.example/api/v1/cameras',
    );
  });

  it('keeps authentication on canonical requests', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('{}', {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }));
    vi.stubGlobal('fetch', fetchMock);
    setCredentials('operator', 'secret');

    await request('/system/info');

    const options = fetchMock.mock.calls[0][1];
    expect(options.headers.get('Authorization')).toBe(
      `Basic ${btoa('operator:secret')}`,
    );
  });

  it('keeps the v1 WebSocket transport outside the REST prefix', () => {
    const url = new URL(webSocketUrl());
    expect(url.pathname).toBe('/ws/plain');
    expect(['ws:', 'wss:']).toContain(url.protocol);
  });
});
