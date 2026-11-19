import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { clearCredentials, setCredentials } from '../api/auth';
import ObserverEventDetail from './ObserverEventDetail';

const EVENT_PATH = '/api/v1/observer/events/obs-1';
const EVENT = {
  id: 'obs-1',
  state: 'COMPLETED',
  mode: 'wildlife',
  triggerReason: 'bird-detection',
  cameraId: 'feeder',
  timestamp: '2026-11-19T20:00:00Z',
  completedAt: '2026-11-19T20:00:01Z',
  bestImagePath: 'observations/obs-1/bird.CR3',
  bestImageFileName: 'bird.CR3',
  previewImagePath: 'observations/obs-1/preview.jpg',
  previewSource: 'live-view-at-trigger',
  aiResult: {
    primaryLabel: 'bird',
    confidence: 0.87,
    provider: 'local',
    model: 'local-bird',
    durationMs: 24,
    failureKind: 'NONE',
    boundingBoxesJson: '[{"x":0.25,"y":0.2,"width":0.3,"height":0.4,"label":"bird","confidence":0.87}]',
  },
};

function response(body, type) {
  return new Response(body, { headers: { 'Content-Type': type } });
}

function setup({ event = EVENT, preview } = {}) {
  const fetcher = vi.fn(async (url) => {
    if (url === EVENT_PATH) return response(JSON.stringify(event), 'application/json');
    if (url === EVENT_PATH + '/image') return preview ?? response('preview', 'image/jpeg');
    if (url === EVENT_PATH + '/original') return response('original', 'application/octet-stream');
    throw new Error('Unexpected request ' + url);
  });
  vi.stubGlobal('fetch', fetcher);
  const view = render(
    <MemoryRouter initialEntries={['/observer/events/obs-1']}>
      <Routes><Route path="/observer/events/:id" element={<ObserverEventDetail />} /></Routes>
    </MemoryRouter>,
  );
  return { ...view, fetcher };
}

beforeEach(() => {
  setCredentials('operator', 'test');
  vi.spyOn(URL, 'createObjectURL')
    .mockReturnValueOnce('blob:preview')
    .mockReturnValueOnce('blob:original');
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {});
});

afterEach(() => {
  cleanup();
  clearCredentials();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('Observer event image and trigger details', () => {
  it('loads the fallback preview with authenticated fetch and identifies the generic detection', async () => {
    const { fetcher, unmount } = setup();
    const image = await screen.findByRole('img', { name: 'Observation preview' });
    expect(image).toHaveAttribute('src', 'blob:preview');
    expect(screen.getByText('Live view at trigger')).toBeInTheDocument();
    expect(screen.getByText('Bird (species not classified)')).toBeInTheDocument();
    expect(screen.queryByText('Species', { exact: true })).toBeNull();
    expect(screen.getByText('bird 87%').parentElement).toHaveStyle({ left: '25%', top: '20%', width: '30%', height: '40%' });
    const [, options] = fetcher.mock.calls.find(([url]) => url === EVENT_PATH + '/image');
    expect(options.headers.get('Authorization')).toBe('Basic ' + btoa('operator:test'));

    unmount();
    expect(options.signal.aborted).toBe(true);
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:preview');
  });

  it('downloads the authenticated original with its camera filename', async () => {
    const { fetcher } = setup();
    await screen.findByRole('img', { name: 'Observation preview' });
    let initiatedDownload;
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function () {
      initiatedDownload = { href: this.href, filename: this.download };
    });
    fireEvent.click(screen.getByRole('button', { name: 'Download original' }));

    await waitFor(() => expect(initiatedDownload).toEqual({ href: 'blob:original', filename: 'bird.CR3' }));
    const [, options] = fetcher.mock.calls.find(([url]) => url === EVENT_PATH + '/original');
    expect(options.headers.get('Authorization')).toBe('Basic ' + btoa('operator:test'));
    await waitFor(() => expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:original'));
  });

  it('ignores preview data that arrives after the event detail is closed', async () => {
    let completePreview;
    const preview = new Promise((resolve) => { completePreview = resolve; });
    const { fetcher, unmount } = setup({ preview });
    await waitFor(() => expect(fetcher.mock.calls.some(([url]) => url === EVENT_PATH + '/image')).toBe(true));
    const [, options] = fetcher.mock.calls.find(([url]) => url === EVENT_PATH + '/image');

    unmount();
    await act(async () => { completePreview(response('preview', 'image/jpeg')); });
    expect(options.signal.aborted).toBe(true);
    expect(URL.createObjectURL).not.toHaveBeenCalled();
  });

  it('keeps trigger boxes off a captured photo preview and retains specific species labels', async () => {
    setup({ event: {
      ...EVENT,
      previewSource: 'capture',
      aiResult: { ...EVENT.aiResult, primaryLabel: 'European robin' },
    } });
    const image = await screen.findByRole('img', { name: 'Observation preview' });
    expect(screen.getByText('Captured photo')).toBeInTheDocument();
    expect(image.parentElement).not.toHaveTextContent('bird 87%');
    expect(screen.getByText('Trigger detections')).toBeInTheDocument();
    expect(screen.getByText('European robin')).toBeInTheDocument();
    expect(screen.getByText('Species', { exact: true })).toBeInTheDocument();
  });
});
