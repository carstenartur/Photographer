import { useEffect, useState } from 'react';
import {
  createAstroSession,
  getAstroSession,
  listAstroSessions,
  abortAstroSession,
  deleteAstroSession,
  downloadAstroResultImage,
} from '../api/astroApi';

const DEFAULT_CAPTURE = {
  frameCount: 10,
  exposureTimeMs: 5000,
  iso: 1600,
  aperture: '',
  delayBetweenFramesMs: 1000,
  imageFormat: 'PNG',
  saveRaw: false,
  saveJpeg: true,
  captureDarkFrame: false,
};

const DEFAULT_FOCUS = {
  strategy: 'MANUAL_FIXED',
  scanSteps: 5,
  scanStepSize: 10,
};

const DEFAULT_STACKING = {
  mode: 'MEAN',
  sigmaThreshold: 2.5,
  maxIterations: 3,
  minimumSamples: 4,
};

const TERMINAL = new Set(['COMPLETED', 'FAILED', 'ABORTED']);

function stackingMode(session) {
  return session?.captureConfig?.stackingConfig?.mode || 'MEAN';
}

function stackingConfig(form) {
  const sigmaThreshold = Number(form.sigmaThreshold);
  const maxIterations = Number(form.maxIterations);
  const minimumSamples = Number(form.minimumSamples);
  if (!Number.isFinite(sigmaThreshold) || sigmaThreshold < 0.1
      || !Number.isInteger(maxIterations) || maxIterations < 1
      || !Number.isInteger(minimumSamples) || minimumSamples < 3) {
    return null;
  }
  return {
    mode: form.mode,
    sigmaThreshold,
    maxIterations,
    minimumSamples,
  };
}

export default function AstroSessions() {
  const [sessions, setSessions] = useState([]);
  const [captureForm, setCaptureForm] = useState(DEFAULT_CAPTURE);
  const [focusForm, setFocusForm] = useState(DEFAULT_FOCUS);
  const [stackingForm, setStackingForm] = useState(DEFAULT_STACKING);
  const [showForm, setShowForm] = useState(false);
  const [activeSession, setActiveSession] = useState(null);
  const [error, setError] = useState(null);
  const [polling, setPolling] = useState(false);

  useEffect(() => {
    let cancelled = false;
    listAstroSessions()
      .then((data) => {
        if (!cancelled) setSessions(data || []);
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, []);

  const activeSessionId = activeSession?.id;
  useEffect(() => {
    if (!activeSessionId || !polling) return;
    const interval = setInterval(async () => {
      try {
        const session = await getAstroSession(activeSessionId);
        setActiveSession(session);
        if (TERMINAL.has(session.status)) {
          setPolling(false);
          listAstroSessions()
            .then((data) => setSessions(data || []))
            .catch(() => {});
        }
      } catch {
        /* ignore polling errors */
      }
    }, 2000);
    return () => clearInterval(interval);
  }, [activeSessionId, polling]);

  function handleCaptureChange(e) {
    const { name, value, type, checked } = e.target;
    setCaptureForm((prev) => ({
      ...prev,
      [name]: type === 'checkbox' ? checked : value,
    }));
  }

  function handleFocusChange(e) {
    const { name, value } = e.target;
    setFocusForm((prev) => ({ ...prev, [name]: value }));
  }

  function handleStackingChange(e) {
    const { name, value } = e.target;
    setStackingForm((prev) => ({ ...prev, [name]: value }));
  }

  async function handleCreate(e) {
    e.preventDefault();
    setError(null);
    const resolvedStackingConfig = stackingConfig(stackingForm);
    if (!resolvedStackingConfig) {
      setError('Create failed: enter valid stacking parameters.');
      return;
    }
    try {
      const config = {
        captureConfig: {
          frameCount: Number(captureForm.frameCount),
          exposureTimeMs: Number(captureForm.exposureTimeMs),
          iso: Number(captureForm.iso),
          aperture: captureForm.aperture || null,
          delayBetweenFramesMs: Number(captureForm.delayBetweenFramesMs),
          imageFormat: captureForm.imageFormat,
          saveRaw: captureForm.saveRaw,
          saveJpeg: captureForm.saveJpeg,
          captureDarkFrame: captureForm.captureDarkFrame,
          stackingConfig: resolvedStackingConfig,
        },
        focusConfig: {
          strategy: focusForm.strategy,
          scanSteps: Number(focusForm.scanSteps),
          scanStepSize: Number(focusForm.scanStepSize),
        },
      };
      const session = await createAstroSession(config);
      setSessions((prev) => [session, ...prev]);
      setActiveSession(session);
      setShowForm(false);
      setPolling(true);
    } catch (err) {
      setError(`Create failed: ${err.message}`);
    }
  }

  async function handleAbort() {
    if (!activeSession) return;
    setError(null);
    try {
      await abortAstroSession(activeSession.id);
      const session = await getAstroSession(activeSession.id);
      setActiveSession(session);
    } catch (err) {
      setError(`Abort failed: ${err.message}`);
    }
  }

  async function handleDelete(sessionId) {
    if (!window.confirm('Are you sure you want to delete this astro session? This will permanently remove the session and all its captured files.')) {
      return;
    }
    setError(null);
    try {
      await deleteAstroSession(sessionId);
      if (activeSession?.id === sessionId) {
        setActiveSession(null);
      }
      const data = await listAstroSessions();
      setSessions(data || []);
    } catch (err) {
      setError(`Delete failed: ${err.message}`);
    }
  }

  async function handleDownloadResult(sessionId) {
    setError(null);
    try {
      const blob = await downloadAstroResultImage(sessionId);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `astro-${sessionId.substring(0, 8)}-result.png`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
    } catch (err) {
      setError(`Download failed: ${err.message}`);
    }
  }

  function selectSession(session) {
    setActiveSession(session);
    setPolling(!TERMINAL.has(session.status));
  }

  const stackingProgress = activeSession?.stackingProgressPercent || 0;

  return (
    <div className="page">
      <div className="page-header">
        <h1>Astro Smart Capture</h1>
        <button className="btn btn-primary" onClick={() => setShowForm((v) => !v)}>
          {showForm ? 'Cancel' : 'New Session'}
        </button>
      </div>

      {error && <p className="error">{error}</p>}

      {showForm && (
        <form className="form-card" onSubmit={handleCreate}>
          <h2>New Astro Session</h2>

          <h3>Capture Settings</h3>
          <label>
            Frame Count
            <input name="frameCount" type="number" min="1" max="1000" value={captureForm.frameCount} onChange={handleCaptureChange} />
          </label>
          <label>
            Exposure Time (ms)
            <input name="exposureTimeMs" type="number" min="100" value={captureForm.exposureTimeMs} onChange={handleCaptureChange} />
          </label>
          <label>
            ISO
            <input name="iso" type="number" min="100" max="51200" value={captureForm.iso} onChange={handleCaptureChange} />
          </label>
          <label>
            Aperture (optional)
            <input name="aperture" type="text" placeholder="e.g. f/2.8" value={captureForm.aperture} onChange={handleCaptureChange} />
          </label>
          <label>
            Delay Between Frames (ms)
            <input name="delayBetweenFramesMs" type="number" min="0" value={captureForm.delayBetweenFramesMs} onChange={handleCaptureChange} />
          </label>
          <label className="checkbox-label">
            <input name="saveRaw" type="checkbox" checked={captureForm.saveRaw} onChange={handleCaptureChange} />
            Save Raw
          </label>
          <label className="checkbox-label">
            <input name="captureDarkFrame" type="checkbox" checked={captureForm.captureDarkFrame} onChange={handleCaptureChange} />
            Capture Dark Frame
          </label>

          <h3>Stacking Settings</h3>
          <label>
            Algorithm
            <select name="mode" value={stackingForm.mode} onChange={handleStackingChange}>
              <option value="MEAN">Mean (historical)</option>
              <option value="MEDIAN">Median</option>
              <option value="SIGMA_CLIPPED_MEAN">Sigma-clipped mean</option>
            </select>
          </label>
          {stackingForm.mode === 'SIGMA_CLIPPED_MEAN' && (
            <>
              <label>
                Sigma Threshold
                <input required name="sigmaThreshold" type="number" min="0.1" step="0.1" value={stackingForm.sigmaThreshold} onChange={handleStackingChange} />
              </label>
              <label>
                Maximum Iterations
                <input required name="maxIterations" type="number" min="1" max="20" value={stackingForm.maxIterations} onChange={handleStackingChange} />
              </label>
              <label>
                Minimum Samples
                <input required name="minimumSamples" type="number" min="3" value={stackingForm.minimumSamples} onChange={handleStackingChange} />
              </label>
            </>
          )}

          <h3>Focus Settings</h3>
          <label>
            Strategy
            <select name="strategy" value={focusForm.strategy} onChange={handleFocusChange}>
              <option value="MANUAL_FIXED">Manual Fixed</option>
              <option value="FOCUS_SCAN">Focus Scan</option>
            </select>
          </label>
          {focusForm.strategy === 'FOCUS_SCAN' && (
            <>
              <label>
                Scan Steps
                <input name="scanSteps" type="number" min="3" max="20" value={focusForm.scanSteps} onChange={handleFocusChange} />
              </label>
              <label>
                Scan Step Size
                <input name="scanStepSize" type="number" min="1" value={focusForm.scanStepSize} onChange={handleFocusChange} />
              </label>
            </>
          )}

          <button type="submit" className="btn btn-primary">
            Start Astro Session
          </button>
        </form>
      )}

      {activeSession && (
        <div className="form-card">
          <h2>Session: {activeSession.id.substring(0, 8)}…</h2>
          <p>
            <strong>Status:</strong>{' '}
            <span className={`badge badge-${(activeSession.status || '').toLowerCase()}`}>
              {activeSession.status}
            </span>
          </p>
          <p><strong>Frames:</strong> {activeSession.captureConfig?.frameCount || 0}</p>
          <p><strong>Focus:</strong> {activeSession.focusConfig?.strategy || '—'}</p>
          <p><strong>Stacking:</strong> {stackingMode(activeSession)}</p>
          {activeSession.status === 'STACKING' && (
            <p>
              <strong>Stacking progress:</strong>{' '}
              <progress
                aria-label="Stacking progress"
                max="100"
                value={stackingProgress}
              />{' '}
              {stackingProgress}%
            </p>
          )}
          {activeSession.stackingSummary && (
            <p><strong>Result:</strong> {activeSession.stackingSummary}</p>
          )}
          {activeSession.resultImagePath && (
            <p><strong>Output:</strong> {activeSession.resultImagePath}</p>
          )}
          {activeSession.errorMessage && <p className="error">{activeSession.errorMessage}</p>}

          <div style={{ display: 'flex', gap: '0.5rem', marginTop: '1rem' }}>
            {!TERMINAL.has(activeSession.status) && (
              <button className="btn btn-danger" onClick={handleAbort}>Abort</button>
            )}
            {activeSession.status === 'COMPLETED' && (
              <button className="btn btn-primary" onClick={() => handleDownloadResult(activeSession.id)}>
                Download Result
              </button>
            )}
            {TERMINAL.has(activeSession.status) && (
              <button className="btn btn-danger" onClick={() => handleDelete(activeSession.id)}>
                Delete Session
              </button>
            )}
          </div>
        </div>
      )}

      <div style={{ marginTop: '2rem' }}>
        <h2>Sessions</h2>
        {sessions.length === 0 ? (
          <p>No astro sessions yet. Click &quot;New Session&quot; to start one.</p>
        ) : (
          <table>
            <thead>
              <tr>
                <th>Session ID</th>
                <th>Status</th>
                <th>Frames</th>
                <th>Focus</th>
                <th>Stacking</th>
                <th>Created</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {sessions.map((s) => (
                <tr key={s.id} onClick={() => selectSession(s)} style={{ cursor: 'pointer' }}>
                  <td>{s.id.substring(0, 8)}…</td>
                  <td>
                    <span className={`badge badge-${(s.status || '').toLowerCase()}`}>
                      {s.status}
                    </span>
                  </td>
                  <td>{s.captureConfig?.frameCount || '—'}</td>
                  <td>{s.focusConfig?.strategy || '—'}</td>
                  <td>{stackingMode(s)}</td>
                  <td>{s.createdAt ? new Date(s.createdAt).toLocaleString() : '—'}</td>
                  <td className="action-cell">
                    {TERMINAL.has(s.status) && (
                      <button
                        className="btn btn-small btn-danger"
                        onClick={(e) => { e.stopPropagation(); handleDelete(s.id); }}
                        title="Delete session and files"
                      >
                        Delete
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
