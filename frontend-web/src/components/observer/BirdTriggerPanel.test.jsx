import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import ObserverDashboard from '../../pages/ObserverDashboard';

const LIVE_PATH = '/api/v1/observer/live';
const SETTINGS = {
  roi: { x: 0, y: 0, width: 1, height: 1, label: 'observation' },
  minConfidence: 0.5,
  minArea: 0.0025,
  intervalMs: 500,
  repeatSeconds: 5,
  absenceSeconds: 3,
};
const PREVIEW = {
  sequence: 12,
  timestamp: '2026-11-19T17:00:00Z',
  width: 960,
  height: 640,
  imageData: 'Y2FtZXJh',
  boundingBoxes: [{ x: 0.25, y: 0.2, width: 0.3, height: 0.4, label: 'bird', confidence: 0.87 }],
};
const STOPPED = {
  cameraId: null,
  running: false,
  armed: false,
  phase: 'STOPPED',
  model: 'Local bird model',
  modelAvailable: true,
  modelError: null,
  settings: SETTINGS,
  captureCount: 0,
  framesAnalyzed: 0,
  skippedFrames: 0,
  frameAgeMs: null,
  inferenceMs: null,
  lastCaptureAt: null,
  lastEventId: null,
  error: null,
  preview: null,
};
const RUNNING = {
  ...STOPPED,
  cameraId: 'feeder',
  running: true,
  phase: 'OBSERVING',
  framesAnalyzed: 12,
  skippedFrames: 2,
  frameAgeMs: 65,
  inferenceMs: 14,
  preview: PREVIEW,
};

function json(data, status = 200) {
  return new Response(JSON.stringify(data), { status });
}

function mockServer({ initial = STOPPED, onStatus } = {}) {
  let status = initial;
  const writes = [];
  const fetcher = vi.fn(async (url, options) => {
    if (url === '/api/v1/cameras') return json([{ id: 'feeder', name: 'Feeder camera' }]);
    if (url === LIVE_PATH) return onStatus ? onStatus(status, options) : json(status);
    if (url.startsWith(`${LIVE_PATH}/`)) {
      const body = JSON.parse(options.body);
      writes.push({ url, method: options.method, body });
      if (url === `${LIVE_PATH}/stop`) status = { ...status, phase: 'STOPPING', armed: false };
      else status = { ...RUNNING, ...body };
      return json(status);
    }
    if (url === '/api/v1/observer/stats' || url === '/api/v1/observer/status') return json({});
    return json([]);
  });
  vi.stubGlobal('fetch', fetcher);
  return { writes, fetcher };
}

function renderObserver() {
  return render(<MemoryRouter><ObserverDashboard /></MemoryRouter>);
}

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('Observer live bird controls', () => {
  it('starts a selected camera in preview and requires an explicit action to arm capture', async () => {
    const { writes } = mockServer();
    renderObserver();

    await screen.findByRole('option', { name: 'Feeder camera' });
    fireEvent.change(screen.getByLabelText('Camera'), { target: { value: 'feeder' } });
    fireEvent.click(screen.getByRole('button', { name: 'Start preview' }));
    await screen.findByText('Preview only');

    expect(writes).toEqual([{ url: `${LIVE_PATH}/start`, method: 'POST', body: {
      cameraId: 'feeder', armed: false, settings: SETTINGS,
    } }]);
    expect(screen.getByLabelText('Camera')).toBeDisabled();

    fireEvent.click(screen.getByRole('button', { name: 'Arm automatic capture' }));
    await screen.findByText('Automatic capture armed');
    expect(writes[1]).toEqual({ url: `${LIVE_PATH}/settings`, method: 'PUT', body: {
      cameraId: 'feeder', armed: true, settings: SETTINGS,
    } });

    fireEvent.click(screen.getByRole('button', { name: 'Stop observer' }));
    await screen.findByText('Stopping');
    expect(writes[2]).toEqual({ url: `${LIVE_PATH}/stop`, method: 'POST', body: {} });
    expect(screen.getByRole('button', { name: 'Stop observer' })).toBeDisabled();
  });

  it('rejects out-of-frame numeric regions and applies valid edits without arming', async () => {
    const { writes } = mockServer({ initial: RUNNING });
    renderObserver();

    const apply = await screen.findByRole('button', { name: 'Apply settings' });
    fireEvent.change(screen.getByLabelText('Left'), { target: { value: '0.25' } });
    fireEvent.click(apply);
    expect(await screen.findByRole('alert')).toHaveTextContent(/inside the frame/i);
    expect(writes).toHaveLength(0);

    fireEvent.change(screen.getByLabelText('Width'), { target: { value: '0.7' } });
    fireEvent.change(screen.getByLabelText('Repeat interval (s)'), { target: { value: '0' } });
    fireEvent.click(apply);

    expect(writes).toEqual([{ url: `${LIVE_PATH}/settings`, method: 'PUT', body: {
      cameraId: 'feeder', armed: false, settings: {
        ...SETTINGS,
        roi: { x: 0.25, y: 0, width: 0.7, height: 1, label: 'observation' },
        repeatSeconds: 0,
      },
    } }]);
    await screen.findByText('Preview only');
  });

  it('displays detections with the analysed frame and exposes measured timings', async () => {
    mockServer({ initial: RUNNING });
    renderObserver();

    const frame = await screen.findByRole('img', { name: 'Latest analysed camera frame' });
    expect(frame).toHaveAttribute('src', 'data:image/jpeg;base64,Y2FtZXJh');
    expect(screen.getByText('bird 87%').parentElement).toHaveStyle({ left: '25%', top: '20%', width: '30%', height: '40%' });
    expect(screen.getByText('65 ms')).toBeInTheDocument();
    expect(screen.getByText('14 ms')).toBeInTheDocument();
  });

  it('maps a touch drag in a scaled preview to full-frame normalized coordinates', async () => {
    const { writes } = mockServer({ initial: RUNNING });
    renderObserver();

    const frame = await screen.findByRole('img', { name: 'Latest analysed camera frame' });
    const surface = frame.parentElement;
    surface.getBoundingClientRect = () => ({ left: 20, top: 40, width: 400, height: 200 });
    fireEvent.click(screen.getByRole('button', { name: 'Draw region' }));
    fireEvent.pointerDown(surface, { pointerId: 1, pointerType: 'touch', button: 0, clientX: 340, clientY: 200 });
    fireEvent.pointerMove(surface, { pointerId: 1, pointerType: 'touch', clientX: 100, clientY: 80 });
    fireEvent.pointerUp(surface, { pointerId: 1, pointerType: 'touch', clientX: 100, clientY: 80 });
    fireEvent.click(screen.getByRole('button', { name: 'Apply settings' }));

    expect(writes[0].body.settings.roi).toEqual({ x: 0.2, y: 0.2, width: 0.6, height: 0.6, label: 'observation' });
    await screen.findByText('Preview only');
  });

  it('surfaces the unavailable model reason and blocks starting a run', async () => {
    const { writes } = mockServer({ initial: {
      ...STOPPED, modelAvailable: false, modelError: 'Model file is missing',
    } });
    renderObserver();

    expect(await screen.findByText(/Model file is missing/)).toBeInTheDocument();
    await screen.findByRole('option', { name: 'Feeder camera' });
    fireEvent.change(screen.getByLabelText('Camera'), { target: { value: 'feeder' } });
    expect(screen.getByRole('button', { name: 'Start preview' })).toBeDisabled();
    expect(writes).toHaveLength(0);
  });

  it('shows a live status API failure and keeps the confirmed stop action available', async () => {
    vi.useFakeTimers();
    let broken = false;
    mockServer({ initial: RUNNING, onStatus(status) {
      return broken ? json({ title: 'Unavailable', detail: 'Live status connection closed' }, 503) : json(status);
    } });
    renderObserver();
    await act(async () => {});
    broken = true;
    await act(async () => { await vi.advanceTimersByTimeAsync(1000); });

    expect(screen.getByRole('alert')).toHaveTextContent(/Live status connection closed/);
    expect(screen.getByRole('button', { name: 'Arm automatic capture' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Stop observer' })).toBeEnabled();
  });

  it('cancels old status requests so they cannot overwrite a newer control response', async () => {
    vi.useFakeTimers();
    let staleSignal;
    let deliverStale;
    const { fetcher } = mockServer({ onStatus(status, options) {
      const requests = fetcher.mock.calls.filter(([url]) => url === LIVE_PATH);
      if (requests.length !== 2) return json(status);
      staleSignal = options.signal;
      return new Promise((resolve) => { deliverStale = () => resolve(json(STOPPED)); });
    } });
    const { unmount } = renderObserver();
    await act(async () => {});
    fireEvent.change(screen.getByLabelText('Camera'), { target: { value: 'feeder' } });

    await act(async () => { await vi.advanceTimersByTimeAsync(3000); });
    expect(fetcher.mock.calls.filter(([url]) => url === LIVE_PATH)).toHaveLength(2);
    expect(staleSignal.aborted).toBe(false);
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Start preview' })); });
    expect(staleSignal.aborted).toBe(true);
    await act(async () => { deliverStale(); });
    expect(screen.getByText('Preview only')).toBeInTheDocument();

    unmount();
    const statusRequests = fetcher.mock.calls.filter(([url]) => url === LIVE_PATH);
    expect(statusRequests.at(-1)[1].signal.aborted).toBe(true);
    await act(async () => { await vi.advanceTimersByTimeAsync(3000); });
    expect(fetcher.mock.calls.filter(([url]) => url === LIVE_PATH)).toHaveLength(statusRequests.length);
  });
});
