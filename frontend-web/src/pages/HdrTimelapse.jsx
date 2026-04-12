import { useEffect, useState } from 'react';
import {
  createHdrSession,
  getHdrSession,
  startHdrSession,
  stopHdrSession,
  getHdrBrackets,
} from '../api/hdrTimelapseApi';

const DEFAULT_SETTINGS = {
  intervalSeconds: 10,
  bracketCount: 3,
  evStep: 1.0,
  totalIntervals: 100,
  saveRaw: false,
  tonemapEnabled: true,
};

export default function HdrTimelapse() {
  const [sessions, setSessions] = useState([]);
  const [form, setForm] = useState(DEFAULT_SETTINGS);
  const [showForm, setShowForm] = useState(false);
  const [activeSession, setActiveSession] = useState(null);
  const [brackets, setBrackets] = useState([]);
  const [error, setError] = useState(null);
  const [polling, setPolling] = useState(false);

  // Poll active session status
  useEffect(() => {
    if (!activeSession || !polling) return;
    const interval = setInterval(async () => {
      try {
        const session = await getHdrSession(activeSession.sessionId);
        setActiveSession(session);
        if (session.status === 'COMPLETED' || session.status === 'FAILED' || session.status === 'CANCELLED') {
          setPolling(false);
        }
        const b = await getHdrBrackets(session.sessionId);
        setBrackets(b || []);
      } catch {
        /* ignore polling errors */
      }
    }, 2000);
    return () => clearInterval(interval);
  }, [activeSession, polling]);

  function handleChange(e) {
    const { name, value, type, checked } = e.target;
    setForm((prev) => ({ ...prev, [name]: type === 'checkbox' ? checked : value }));
  }

  async function handleCreate(e) {
    e.preventDefault();
    setError(null);
    try {
      const settings = {
        intervalSeconds: Number(form.intervalSeconds),
        bracketCount: Number(form.bracketCount),
        evStep: Number(form.evStep),
        totalIntervals: Number(form.totalIntervals),
        saveRaw: form.saveRaw,
        tonemapEnabled: form.tonemapEnabled,
      };
      const session = await createHdrSession(settings);
      setSessions((prev) => [session, ...prev]);
      setActiveSession(session);
      setShowForm(false);
    } catch (err) {
      setError(`Create failed: ${err.message}`);
    }
  }

  async function handleStart() {
    if (!activeSession) return;
    setError(null);
    try {
      const session = await startHdrSession(activeSession.sessionId);
      setActiveSession(session);
      setPolling(true);
    } catch (err) {
      setError(`Start failed: ${err.message}`);
    }
  }

  async function handleStop() {
    if (!activeSession) return;
    setError(null);
    try {
      await stopHdrSession(activeSession.sessionId);
      const session = await getHdrSession(activeSession.sessionId);
      setActiveSession(session);
      setPolling(false);
    } catch (err) {
      setError(`Stop failed: ${err.message}`);
    }
  }

  function selectSession(session) {
    setActiveSession(session);
    setBrackets([]);
    if (session.status === 'RUNNING') {
      setPolling(true);
    }
  }

  return (
    <div className="page">
      <div className="page-header">
        <h1>HDR Timelapse</h1>
        <button
          className="btn btn-primary"
          onClick={() => setShowForm((v) => !v)}
        >
          {showForm ? 'Cancel' : 'New Session'}
        </button>
      </div>

      {error && <p className="error">{error}</p>}

      {showForm && (
        <form className="form-card" onSubmit={handleCreate}>
          <h2>New HDR Timelapse Session</h2>
          <label>
            Interval (seconds)
            <input name="intervalSeconds" type="number" min="1" value={form.intervalSeconds} onChange={handleChange} />
          </label>
          <label>
            Bracket Count
            <input name="bracketCount" type="number" min="2" max="9" value={form.bracketCount} onChange={handleChange} />
          </label>
          <label>
            EV Step
            <input name="evStep" type="number" step="0.5" min="0.5" value={form.evStep} onChange={handleChange} />
          </label>
          <label>
            Total Intervals
            <input name="totalIntervals" type="number" min="1" value={form.totalIntervals} onChange={handleChange} />
          </label>
          <label className="checkbox-label">
            <input name="saveRaw" type="checkbox" checked={form.saveRaw} onChange={handleChange} />
            Save Raw Files
          </label>
          <label className="checkbox-label">
            <input name="tonemapEnabled" type="checkbox" checked={form.tonemapEnabled} onChange={handleChange} />
            Enable Tonemapping
          </label>
          <button type="submit" className="btn btn-primary">Create Session</button>
        </form>
      )}

      {activeSession && (
        <div className="form-card">
          <h2>Session: {activeSession.sessionId.substring(0, 8)}…</h2>
          <p>
            <strong>Status:</strong>{' '}
            <span className={`badge badge-${(activeSession.status || '').toLowerCase()}`}>
              {activeSession.status}
            </span>
          </p>
          <p><strong>Type:</strong> {activeSession.type}</p>
          <p><strong>Frames:</strong> {activeSession.frameIds ? activeSession.frameIds.length : 0}</p>
          {activeSession.errorMessage && (
            <p className="error">{activeSession.errorMessage}</p>
          )}

          <div style={{ display: 'flex', gap: '0.5rem', marginTop: '1rem' }}>
            {(activeSession.status === 'CREATED' || activeSession.status === 'PAUSED') && (
              <button className="btn btn-primary" onClick={handleStart}>Start</button>
            )}
            {activeSession.status === 'RUNNING' && (
              <button className="btn btn-danger" onClick={handleStop}>Stop</button>
            )}
          </div>

          {brackets.length > 0 && (
            <div style={{ marginTop: '1rem' }}>
              <h3>Brackets ({brackets.length})</h3>
              <table>
                <thead>
                  <tr>
                    <th>#</th>
                    <th>Bracket ID</th>
                    <th>Frames</th>
                    <th>Captured</th>
                  </tr>
                </thead>
                <tbody>
                  {brackets.map((b, i) => (
                    <tr key={b.bracketSetId}>
                      <td>{i + 1}</td>
                      <td>{b.bracketSetId.substring(0, 8)}…</td>
                      <td>{b.frames ? b.frames.length : 0}</td>
                      <td>{b.capturedAt ? new Date(b.capturedAt).toLocaleString() : '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      {sessions.length > 0 && (
        <div style={{ marginTop: '2rem' }}>
          <h2>Sessions</h2>
          <table>
            <thead>
              <tr>
                <th>Session ID</th>
                <th>Status</th>
                <th>Frames</th>
                <th>Created</th>
              </tr>
            </thead>
            <tbody>
              {sessions.map((s) => (
                <tr
                  key={s.sessionId}
                  onClick={() => selectSession(s)}
                  style={{ cursor: 'pointer' }}
                >
                  <td>{s.sessionId.substring(0, 8)}…</td>
                  <td>
                    <span className={`badge badge-${(s.status || '').toLowerCase()}`}>
                      {s.status}
                    </span>
                  </td>
                  <td>{s.frameIds ? s.frameIds.length : 0}</td>
                  <td>{s.createdAt ? new Date(s.createdAt).toLocaleString() : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
