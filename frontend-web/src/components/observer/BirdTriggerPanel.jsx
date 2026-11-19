import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { getAllCameras } from '../../api/cameraApi';
import {
  getLiveObserverStatus,
  startLiveObserver,
  stopLiveObserver,
  updateLiveObserverSettings,
} from '../../api/observerApi';
import BirdTriggerPreview from './BirdTriggerPreview';
import './BirdTriggerPanel.css';

const FULL_REGION = { x: 0, y: 0, width: 1, height: 1, label: 'observation' };
const DEFAULT_SETTINGS = {
  roi: FULL_REGION,
  minConfidence: 0.5,
  minArea: 0.0025,
  intervalMs: 500,
  repeatSeconds: 5,
  absenceSeconds: 3,
};
const PHASE_LABELS = {
  STOPPED: 'Stopped',
  STARTING: 'Starting',
  OBSERVING: 'Observing',
  CAPTURING: 'Capturing',
  STOPPING: 'Stopping',
  ERROR: 'Error',
};
const NUMBER_SETTINGS = [
  { key: 'minConfidence', label: 'Minimum confidence', min: 0.1, max: 1 },
  { key: 'minArea', label: 'Minimum bird area', min: 0, max: 1 },
  { key: 'intervalMs', label: 'Analysis interval (ms)', min: 200, max: 5000, integer: true },
  { key: 'repeatSeconds', label: 'Repeat interval (s)', min: 0, max: 3600, integer: true },
  { key: 'absenceSeconds', label: 'Absence to reset visit (s)', min: 1, max: 60, integer: true },
];
const REGION_FIELDS = [
  ['x', 'Left'],
  ['y', 'Top'],
  ['width', 'Width'],
  ['height', 'Height'],
];

function parseRegion(region) {
  const { label, x, y, width, height } = region;
  if ([x, y, width, height].some((value) => value === '' || !Number.isFinite(Number(value)))) return null;
  const roi = { x: Number(x), y: Number(y), width: Number(width), height: Number(height), label };
  if (
    roi.x < 0 || roi.y < 0 || roi.width <= 0 || roi.height <= 0 ||
    roi.x + roi.width > 1 || roi.y + roi.height > 1
  ) return null;
  return roi;
}

function parseSettings(draft) {
  const roi = parseRegion(draft.roi);
  if (!roi) throw new Error('The observation region must fit inside the frame and have a positive width and height.');
  const settings = { roi };
  for (const { key, label, min, max, integer } of NUMBER_SETTINGS) {
    const value = Number(draft[key]);
    if (
      draft[key] === '' || !Number.isFinite(value) || value < min || value > max ||
      (integer && !Number.isInteger(value))
    ) {
      const kind = integer ? 'a whole number' : 'a number';
      throw new Error(label + ' must be ' + kind + ' from ' + min + ' to ' + max + '.');
    }
    settings[key] = value;
  }
  return settings;
}

function Timing({ value }) {
  return Number.isFinite(value) ? Math.round(value) + ' ms' : '—';
}

export default function BirdTriggerPanel() {
  const [cameras, setCameras] = useState(null);
  const [cameraError, setCameraError] = useState(null);
  const [selectedCamera, setSelectedCamera] = useState('');
  const [status, setStatus] = useState(null);
  const [statusError, setStatusError] = useState(null);
  const [actionError, setActionError] = useState(null);
  const [pending, setPending] = useState(false);
  const [draft, setDraft] = useState(null);
  const polling = useRef(null);
  const command = useRef(null);

  useEffect(() => {
    const controller = new AbortController();
    getAllCameras({ signal: controller.signal }).then(
      (list) => { if (!controller.signal.aborted) setCameras(list ?? []); },
      (err) => { if (!controller.signal.aborted) setCameraError(err.message); },
    );
    return () => controller.abort();
  }, []);

  useEffect(() => {
    if (pending) return;
    const controller = new AbortController();
    polling.current = controller;
    let timer;
    async function poll() {
      try {
        const next = await getLiveObserverStatus(controller.signal);
        if (controller.signal.aborted) return;
        setStatus(next);
        setStatusError(null);
      } catch (err) {
        if (controller.signal.aborted) return;
        setStatusError(err.message);
      }
      timer = setTimeout(poll, 1000);
    }
    poll();
    return () => {
      controller.abort();
      clearTimeout(timer);
    };
  }, [pending]);

  useEffect(() => () => command.current?.abort(), []);

  const running = status?.running === true;
  const armed = status?.armed === true;
  const phase = status?.phase;
  const currentCamera = running ? status.cameraId : selectedCamera || status?.cameraId || '';
  const activeSettings = status?.settings ?? DEFAULT_SETTINGS;
  const settings = draft ?? activeSettings;
  const canEdit = !pending && phase !== 'STARTING' && phase !== 'STOPPING';
  const canStart = canEdit && !statusError && status?.modelAvailable === true &&
    cameras?.some(({ id }) => id === currentCamera);
  const canUpdate = canEdit && running && !statusError && status?.modelAvailable === true;
  const mode = statusError ? 'Status unavailable'
    : !status ? 'Loading live status…'
    : armed ? 'Automatic capture armed'
      : running ? 'Preview only' : 'Idle';
  const model = statusError ? 'Unknown'
    : status?.modelAvailable === true ? status.model || 'Available'
      : status?.modelAvailable === false ? 'Unavailable' : 'Unknown';

  function changeSettings(changes) {
    setDraft((previous) => ({ ...(previous ?? activeSettings), ...changes }));
    setActionError(null);
  }

  function changeRegion(changes) {
    setDraft((previous) => {
      const current = previous ?? activeSettings;
      return { ...current, roi: { ...current.roi, ...changes } };
    });
    setActionError(null);
  }

  async function runCommand(request, description, { clearDraft = false } = {}) {
    if (command.current) return;
    const controller = new AbortController();
    command.current = controller;
    polling.current?.abort();
    setPending(true);
    setActionError(null);
    try {
      const next = await request(controller.signal);
      if (controller.signal.aborted) return;
      setStatus(next);
      setStatusError(null);
      setSelectedCamera(currentCamera);
      if (clearDraft) setDraft(null);
    } catch (err) {
      if (!controller.signal.aborted) setActionError(description + ' failed. ' + err.message);
    } finally {
      if (command.current === controller) command.current = null;
      if (!controller.signal.aborted) setPending(false);
    }
  }

  function applySettings(nextArmed) {
    try {
      const config = { cameraId: currentCamera, armed: nextArmed, settings: parseSettings(settings) };
      const request = running ? updateLiveObserverSettings : startLiveObserver;
      const description = running ? 'Update live observer' : 'Start preview';
      runCommand((signal) => request(config, signal), description, { clearDraft: true });
    } catch (err) {
      setActionError(err.message);
    }
  }

  function submit(event) {
    event.preventDefault();
    if ((!running && canStart) || canUpdate) applySettings(running && armed);
  }

  function disarm() {
    const config = { cameraId: currentCamera, armed: false, settings: activeSettings };
    runCommand((signal) => updateLiveObserverSettings(config, signal), 'Disarm automatic capture');
  }

  return (
    <section className="detail-section bird-trigger" aria-labelledby="bird-trigger-title">
      <h2 id="bird-trigger-title">Live bird detection</h2>
      <dl className="bird-trigger-status">
        <div><dt>Phase</dt><dd role="status">{statusError && phase ? 'Last known: ' : ''}{PHASE_LABELS[phase] ?? '—'}</dd></div>
        <div><dt>Mode</dt><dd role="status">{mode}</dd></div>
        <div><dt>Local model</dt><dd>{model}</dd></div>
        <div><dt>Frame age</dt><dd><Timing value={statusError ? null : status?.frameAgeMs} /></dd></div>
        <div><dt>Inference</dt><dd><Timing value={statusError ? null : status?.inferenceMs} /></dd></div>
        <div><dt>Frames analysed</dt><dd>{status?.framesAnalyzed ?? '—'}</dd></div>
        <div><dt>Frames skipped</dt><dd>{status?.skippedFrames ?? '—'}</dd></div>
        <div><dt>Captures</dt><dd>{status?.captureCount ?? '—'}</dd></div>
        <div>
          <dt>Last capture</dt>
          <dd>
            {status?.lastCaptureAt ? (
              <time dateTime={status.lastCaptureAt}>{new Date(status.lastCaptureAt).toLocaleString()}</time>
            ) : '—'}
            {status?.lastEventId && (
              <> <Link to={'/observer/events/' + encodeURIComponent(status.lastEventId)}>View observation</Link></>
            )}
          </dd>
        </div>
      </dl>
      {status?.modelError && <p className="error" role="alert">{status.modelError}</p>}
      {status?.error && <p className="error" role="alert">{status.error}</p>}
      {statusError && <p className="error" role="alert">Could not load live observer status. {statusError}</p>}
      {cameraError && <p className="error" role="alert">Could not load cameras. {cameraError}</p>}
      {actionError && <p className="error" role="alert">{actionError}</p>}

      <div className="bird-trigger-layout">
        <BirdTriggerPreview
          key={currentCamera}
          preview={status?.cameraId === currentCamera ? status.preview : null}
          roi={parseRegion(settings.roi)}
          onRegionChange={changeRegion}
          canEdit={canEdit}
          running={running}
        />
        <form className="bird-trigger-controls" onSubmit={submit} noValidate>
          <label>
            Camera
            <select
              value={currentCamera}
              onChange={(event) => setSelectedCamera(event.target.value)}
              disabled={running || !canEdit || !cameras?.length}
            >
              <option value="">{cameras === null ? 'Loading cameras…' : cameras.length ? 'Select a camera' : 'No cameras configured'}</option>
              {cameras?.map(({ id, name }) => <option key={id} value={id}>{name}</option>)}
              {running && currentCamera && !cameras?.some(({ id }) => id === currentCamera) && (
                <option value={currentCamera}>{currentCamera}</option>
              )}
            </select>
          </label>
          <div className="bird-trigger-actions">
            {running ? (
              <>
                <button type="submit" className="btn" disabled={!canUpdate}>Apply settings</button>
                {armed ? (
                  <button type="button" className="btn" disabled={!canEdit} onClick={disarm}>Disarm automatic capture</button>
                ) : (
                  <button type="button" className="btn btn-primary" disabled={!canUpdate} onClick={() => applySettings(true)}>
                    Arm automatic capture
                  </button>
                )}
                <button
                  type="button"
                  className="btn btn-danger"
                  disabled={pending || phase === 'STOPPING'}
                  onClick={() => runCommand(stopLiveObserver, 'Stop observer')}
                >
                  Stop observer
                </button>
              </>
            ) : (
              <button type="submit" className="btn btn-primary" disabled={!canStart}>Start preview</button>
            )}
          </div>
          <fieldset disabled={!canEdit}>
            <legend>Observation region</legend>
            <p>Use coordinates from 0 to 1 relative to the full frame.{running && draft && ' Apply settings to use the edits.'}</p>
            <div className="bird-trigger-fields">
              {REGION_FIELDS.map(([key, label]) => (
                <label key={key}>
                  {label}
                  <input
                    type="number"
                    min="0"
                    max="1"
                    step="any"
                    value={settings.roi[key]}
                    onChange={(event) => changeRegion({ [key]: event.target.value })}
                  />
                </label>
              ))}
            </div>
            <button type="button" className="btn btn-small" onClick={() => changeRegion(FULL_REGION)}>
              Use full frame
            </button>
          </fieldset>
          <fieldset disabled={!canEdit}>
            <legend>Detection and capture</legend>
            <div className="bird-trigger-fields">
              {NUMBER_SETTINGS.map(({ key, label, min, max, integer }) => (
                <label key={key}>
                  {label}
                  <input
                    type="number"
                    min={min}
                    max={max}
                    step={integer ? 1 : 'any'}
                    value={settings[key]}
                    onChange={(event) => changeSettings({ [key]: event.target.value })}
                  />
                </label>
              ))}
            </div>
            <p>Minimum bird area is a fraction of the region. A repeat interval of 0 takes one photo per visit.</p>
          </fieldset>
        </form>
      </div>
    </section>
  );
}
