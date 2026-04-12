import { useEffect, useState } from 'react';
import {
  createFaceOrbitSession,
  getFaceOrbitSession,
  captureNextStep,
  startAutoMode,
  stopFaceOrbitSession,
  completeFaceOrbitSession,
  getQualityChecks,
  generateProfile,
  getProfileJob,
} from '../api/faceOrbitApi';

const DEFAULT_SETTINGS = {
  totalSteps: 18,
  angleRange: 180.0,
  autoMode: false,
  qualityCheckEnabled: true,
  blurThreshold: 0.5,
  exposureThreshold: 0.3,
  faceDetectionRequired: true,
};

export default function FaceOrbit() {
  const [form, setForm] = useState(DEFAULT_SETTINGS);
  const [showForm, setShowForm] = useState(false);
  const [activeSession, setActiveSession] = useState(null);
  const [qualityChecks, setQualityChecks] = useState([]);
  const [profileJob, setProfileJob] = useState(null);
  const [error, setError] = useState(null);
  const [polling, setPolling] = useState(false);

  // Poll active session status
  useEffect(() => {
    if (!activeSession || !polling) return;
    const interval = setInterval(async () => {
      try {
        const session = await getFaceOrbitSession(activeSession.sessionId);
        setActiveSession(session);
        if (session.status === 'COMPLETED' || session.status === 'FAILED' || session.status === 'CANCELLED') {
          setPolling(false);
        }
        const checks = await getQualityChecks(session.sessionId);
        setQualityChecks(checks || []);
      } catch {
        /* ignore polling errors */
      }
    }, 1500);
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
        totalSteps: Number(form.totalSteps),
        angleRange: Number(form.angleRange),
        autoMode: form.autoMode,
        qualityCheckEnabled: form.qualityCheckEnabled,
        blurThreshold: Number(form.blurThreshold),
        exposureThreshold: Number(form.exposureThreshold),
        faceDetectionRequired: form.faceDetectionRequired,
      };
      const session = await createFaceOrbitSession(settings);
      setActiveSession(session);
      setQualityChecks([]);
      setProfileJob(null);
      setShowForm(false);
    } catch (err) {
      setError(`Create failed: ${err.message}`);
    }
  }

  async function handleCapture() {
    if (!activeSession) return;
    setError(null);
    try {
      const quality = await captureNextStep(activeSession.sessionId);
      setQualityChecks((prev) => [...prev, quality]);
      const session = await getFaceOrbitSession(activeSession.sessionId);
      setActiveSession(session);
    } catch (err) {
      setError(`Capture failed: ${err.message}`);
    }
  }

  async function handleAutoMode() {
    if (!activeSession) return;
    setError(null);
    try {
      await startAutoMode(activeSession.sessionId);
      setPolling(true);
    } catch (err) {
      setError(`Auto mode failed: ${err.message}`);
    }
  }

  async function handleStop() {
    if (!activeSession) return;
    setError(null);
    try {
      await stopFaceOrbitSession(activeSession.sessionId);
      const session = await getFaceOrbitSession(activeSession.sessionId);
      setActiveSession(session);
      setPolling(false);
    } catch (err) {
      setError(`Stop failed: ${err.message}`);
    }
  }

  async function handleComplete() {
    if (!activeSession) return;
    setError(null);
    try {
      const session = await completeFaceOrbitSession(activeSession.sessionId);
      setActiveSession(session);
    } catch (err) {
      setError(`Complete failed: ${err.message}`);
    }
  }

  async function handleGenerateProfile() {
    if (!activeSession) return;
    setError(null);
    try {
      const job = await generateProfile(activeSession.sessionId);
      setProfileJob(job);
    } catch (err) {
      setError(`Profile generation failed: ${err.message}`);
    }
  }

  async function handleRefreshProfile() {
    if (!profileJob) return;
    try {
      const job = await getProfileJob(profileJob.jobId);
      setProfileJob(job);
    } catch (err) {
      setError(`Refresh failed: ${err.message}`);
    }
  }

  const currentStep = activeSession?.frameIds?.length || 0;
  const totalSteps = form.totalSteps || 18;
  const progressPct = Math.min(100, Math.round((currentStep / totalSteps) * 100));

  return (
    <div className="page">
      <div className="page-header">
        <h1>Face Orbit Capture</h1>
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
          <h2>New Face Orbit Session</h2>
          <label>
            Total Steps
            <input name="totalSteps" type="number" min="3" max="72" value={form.totalSteps} onChange={handleChange} />
          </label>
          <label>
            Angle Range (°)
            <input name="angleRange" type="number" min="30" max="360" value={form.angleRange} onChange={handleChange} />
          </label>
          <label>
            Blur Threshold
            <input name="blurThreshold" type="number" step="0.1" min="0" max="1" value={form.blurThreshold} onChange={handleChange} />
          </label>
          <label>
            Exposure Threshold
            <input name="exposureThreshold" type="number" step="0.1" min="0" max="1" value={form.exposureThreshold} onChange={handleChange} />
          </label>
          <label className="checkbox-label">
            <input name="autoMode" type="checkbox" checked={form.autoMode} onChange={handleChange} />
            Auto Mode
          </label>
          <label className="checkbox-label">
            <input name="qualityCheckEnabled" type="checkbox" checked={form.qualityCheckEnabled} onChange={handleChange} />
            Quality Checks
          </label>
          <label className="checkbox-label">
            <input name="faceDetectionRequired" type="checkbox" checked={form.faceDetectionRequired} onChange={handleChange} />
            Require Face Detection
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
          <p><strong>Progress:</strong> {currentStep} / {totalSteps} steps ({progressPct}%)</p>
          <div
            style={{
              width: '100%', height: '8px', background: '#334155',
              borderRadius: '4px', overflow: 'hidden', marginBottom: '1rem',
            }}
          >
            <div
              style={{
                width: `${progressPct}%`, height: '100%',
                background: progressPct === 100 ? '#22c55e' : '#3b82f6',
                transition: 'width 0.3s',
              }}
            />
          </div>
          {activeSession.errorMessage && (
            <p className="error">{activeSession.errorMessage}</p>
          )}

          <div style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap' }}>
            {(activeSession.status === 'CREATED' || activeSession.status === 'RUNNING') && (
              <button className="btn btn-primary" onClick={handleCapture}>
                📸 Capture Step {currentStep + 1}
              </button>
            )}
            {activeSession.status === 'CREATED' && (
              <button className="btn btn-secondary" onClick={handleAutoMode}>
                ⚡ Auto Mode
              </button>
            )}
            {activeSession.status === 'RUNNING' && (
              <button className="btn btn-danger" onClick={handleStop}>
                Stop
              </button>
            )}
            {(activeSession.status === 'CREATED' || activeSession.status === 'RUNNING') && currentStep > 0 && (
              <button className="btn btn-secondary" onClick={handleComplete}>
                ✓ Complete
              </button>
            )}
            {activeSession.status === 'COMPLETED' && (
              <button className="btn btn-primary" onClick={handleGenerateProfile}>
                🧊 Generate 3D Profile
              </button>
            )}
          </div>

          {/* Profile Job Status */}
          {profileJob && (
            <div style={{ marginTop: '1rem', padding: '1rem', background: '#1e293b', borderRadius: '8px' }}>
              <h3>3D Profile Job</h3>
              <p>
                <strong>Status:</strong>{' '}
                <span className={`badge badge-${(profileJob.status || '').toLowerCase()}`}>
                  {profileJob.status}
                </span>
              </p>
              {profileJob.vertexCount > 0 && (
                <p><strong>Vertices:</strong> {profileJob.vertexCount}</p>
              )}
              {profileJob.confidence > 0 && (
                <p><strong>Confidence:</strong> {(profileJob.confidence * 100).toFixed(1)}%</p>
              )}
              {profileJob.resultPath && (
                <p><strong>Result:</strong> {profileJob.resultPath}</p>
              )}
              {profileJob.errorMessage && (
                <p className="error">{profileJob.errorMessage}</p>
              )}
              {profileJob.status === 'RUNNING' && (
                <button className="btn btn-small" onClick={handleRefreshProfile}>
                  Refresh
                </button>
              )}
            </div>
          )}

          {/* Quality Checks */}
          {qualityChecks.length > 0 && (
            <div style={{ marginTop: '1rem' }}>
              <h3>Quality Checks ({qualityChecks.length})</h3>
              <table>
                <thead>
                  <tr>
                    <th>#</th>
                    <th>Sharpness</th>
                    <th>Exposure</th>
                    <th>Face</th>
                    <th>Passed</th>
                    <th>Reason</th>
                  </tr>
                </thead>
                <tbody>
                  {qualityChecks.map((qc, i) => (
                    <tr key={qc.frameId || i}>
                      <td>{i + 1}</td>
                      <td>{qc.sharpnessScore?.toFixed(2)}</td>
                      <td>{qc.exposureScore?.toFixed(2)}</td>
                      <td>{qc.faceDetected ? '✓' : '✗'} {qc.faceConfidence ? `(${(qc.faceConfidence * 100).toFixed(0)}%)` : ''}</td>
                      <td>{qc.passed ? '✅' : '❌'}</td>
                      <td>{qc.reason}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
