import { useEffect, useState, useRef } from 'react';
import { useParams, Link } from 'react-router-dom';
import { getJob, startJob, pauseJob, cancelJob } from '../api/jobApi';
import { getCameraStatus } from '../api/cameraApi';
import PreviewPanel from '../components/PreviewPanel';

export default function JobDetail() {
  const { id } = useParams();
  const [job, setJob] = useState(null);
  const [error, setError] = useState(null);
  const [logs, setLogs] = useState([]);
  const [cameraStatus, setCameraStatus] = useState(null);
  const logEndRef = useRef(null);

  useEffect(() => {
    let cancelled = false;
    getJob(id)
      .then((data) => { if (!cancelled) setJob(data); })
      .catch(() => { if (!cancelled) setError('Failed to load job.'); });
    return () => { cancelled = true; };
  }, [id]);

  // Auto-refresh while job is RUNNING
  const jobStatus = job?.status;
  useEffect(() => {
    if (!jobStatus || (jobStatus !== 'RUNNING' && jobStatus !== 'CREATED')) return;
    const interval = setInterval(async () => {
      try {
        const data = await getJob(id);
        setJob(data);
        if (data.status !== 'RUNNING' && data.status !== 'CREATED') {
          clearInterval(interval);
        }
      } catch { /* ignore refresh errors */ }
    }, 2000);
    return () => clearInterval(interval);
  }, [id, jobStatus]);

  // Fetch camera battery status while job is active
  const cameraProfileId = job?.cameraProfileId;
  useEffect(() => {
    if (!cameraProfileId) return;
    const isActive = jobStatus === 'RUNNING' || jobStatus === 'CREATED' || jobStatus === 'PAUSED';
    // Fetch once immediately, then poll only while active
    let cancelled = false;
    async function fetchStatus() {
      try {
        const status = await getCameraStatus(cameraProfileId);
        if (!cancelled) setCameraStatus(status);
      } catch { /* camera status is best-effort */ }
    }
    fetchStatus();
    if (!isActive) return () => { cancelled = true; };
    const interval = setInterval(fetchStatus, 10000);
    return () => { cancelled = true; clearInterval(interval); };
  }, [cameraProfileId, jobStatus]);

  // WebSocket for real-time logs
  useEffect(() => {
    let ws;
    try {
      const wsUrl = (import.meta.env.VITE_API_URL || 'http://localhost:8080')
        .replace(/^http/, 'ws') + '/ws/sockjs';
      // Simple WebSocket for log streaming — falls back gracefully
      ws = new WebSocket(wsUrl.replace('/sockjs', ''));
      ws.onmessage = (event) => {
        try {
          const msg = JSON.parse(event.data);
          if (msg.jobId === id) {
            setLogs((prev) => [...prev.slice(-200), msg]);
          }
        } catch { /* ignore non-JSON */ }
      };
      ws.onerror = () => { /* WebSocket optional — polling works as fallback */ };
    } catch { /* WebSocket not available */ }
    return () => { if (ws) ws.close(); };
  }, [id]);

  useEffect(() => {
    logEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [logs]);

  async function refreshJob() {
    try {
      const data = await getJob(id);
      setJob(data);
    } catch {
      setError('Failed to load job.');
    }
  }

  async function handleAction(action) {
    setError(null);
    try {
      await action(id);
      await refreshJob();
    } catch (err) {
      setError(`Action failed: ${err.message}`);
    }
  }

  if (error && !job) {
    return (
      <div className="page">
        <p className="error">{error}</p>
        <Link to="/jobs">← Back to Jobs</Link>
      </div>
    );
  }

  if (!job) {
    return (
      <div className="page">
        <p>Loading…</p>
      </div>
    );
  }

  const frames = job.frames || [];
  const params = job.parameters || {};
  const maxFrames = params.maxFrames || 0;
  const progress = maxFrames > 0 ? Math.round((frames.length / maxFrames) * 100) : 0;
  const isActive = job.status === 'RUNNING' || job.status === 'CREATED' || job.status === 'PAUSED';
  const batteryLevel = cameraStatus?.batteryLevel;
  const batteryColor = batteryLevel != null
    ? batteryLevel <= 15 ? '#ef4444' : batteryLevel <= 30 ? '#f59e0b' : '#22c55e'
    : undefined;

  return (
    <div className="page">
      <Link to="/jobs" className="back-link">
        ← Back to Jobs
      </Link>

      <div className="page-header">
        <h1>Job {job.id.substring(0, 8)}…</h1>
        <span className={`badge badge-${(job.status || 'UNKNOWN').toLowerCase()}`}>
          {job.status || 'UNKNOWN'}
        </span>
        {isActive && batteryLevel != null && (
          <span className="job-battery" style={{ color: batteryColor }} title={`Camera battery: ${batteryLevel}%`}>
            🔋 {batteryLevel}%
          </span>
        )}
      </div>

      {error && <p className="error">{error}</p>}

      <section className="detail-actions">
        {(job.status === 'CREATED' || job.status === 'PAUSED') && (
          <button
            className="btn btn-primary"
            onClick={() => handleAction(startJob)}
          >
            Start
          </button>
        )}
        {job.status === 'RUNNING' && (
          <button className="btn" onClick={() => handleAction(pauseJob)}>
            Pause
          </button>
        )}
        {(job.status === 'RUNNING' || job.status === 'PAUSED') && (
          <button
            className="btn btn-danger"
            onClick={() => handleAction(cancelJob)}
          >
            Cancel
          </button>
        )}
        <button className="btn" onClick={refreshJob}>
          Refresh
        </button>
      </section>

      <section className="detail-section">
        <h2>Progress</h2>
        <div className="progress-bar-container">
          <div
            className="progress-bar"
            style={{ width: `${progress}%` }}
          />
        </div>
        <p>
          {frames.length} / {maxFrames || '?'} frames captured ({progress}%)
        </p>
      </section>

      <PreviewPanel jobId={id} isRunning={job.status === 'RUNNING'} frames={frames} />

      <section className="detail-section">
        <h2>Parameters</h2>
        <dl className="params">
          <dt>Camera Profile</dt>
          <dd>{job.cameraProfileId || '—'}</dd>
          {cameraStatus && (
            <>
              <dt>Camera Battery</dt>
              <dd>
                <span style={{ color: batteryColor, fontWeight: 600 }}>
                  🔋 {cameraStatus.batteryLevel}%
                </span>
                {cameraStatus.batteryLevel <= 15 && (
                  <span style={{ color: '#ef4444', marginLeft: '0.5rem', fontSize: '0.8rem' }}>
                    ⚠ Low battery
                  </span>
                )}
              </dd>
              <dt>Camera Connected</dt>
              <dd>{cameraStatus.connected ? '✅ Yes' : '❌ No'}</dd>
              {cameraStatus.shutterSpeed && (
                <>
                  <dt>Exposure</dt>
                  <dd>{cameraStatus.shutterSpeed} · f/{cameraStatus.aperture} · ISO {cameraStatus.iso}</dd>
                </>
              )}
            </>
          )}
          <dt>Step Size</dt>
          <dd>{params.stepSize ?? '—'}</dd>
          <dt>Max Frames</dt>
          <dd>{params.maxFrames ?? '—'}</dd>
          <dt>Abort Threshold</dt>
          <dd>{params.abortThreshold ?? '—'}</dd>
          <dt>Motion Detection</dt>
          <dd>{params.motionDetectionEnabled != null ? String(params.motionDetectionEnabled) : '—'}</dd>
          <dt>Quality Mode</dt>
          <dd>{params.qualityMode ?? '—'}</dd>
          <dt>Started</dt>
          <dd>
            {job.startedAt
              ? new Date(job.startedAt).toLocaleString()
              : '—'}
          </dd>
          <dt>Completed</dt>
          <dd>
            {job.completedAt
              ? new Date(job.completedAt).toLocaleString()
              : '—'}
          </dd>
        </dl>
      </section>

      <section className="detail-section">
        <h2>Captured Frames ({frames.length})</h2>
        {frames.length === 0 ? (
          <p className="empty">No frames captured yet.</p>
        ) : (
          <div className="frame-thumb-grid">
            {frames.map((frameId, i) => {
              const imgUrl = `${import.meta.env.VITE_API_URL || 'http://localhost:8080/api'}/jobs/${id}/frames/${frameId}/image`;
              return (
                <div key={frameId} className="frame-thumb-card">
                  <img
                    src={imgUrl}
                    alt={`Frame #${i + 1}`}
                    className="frame-thumb-img"
                    loading="lazy"
                    onError={(e) => { e.target.style.display = 'none'; e.target.nextSibling.style.display = 'flex'; }}
                  />
                  <div className="frame-thumb-placeholder" style={{ display: 'none' }}>
                    No image data
                  </div>
                  <div className="frame-thumb-label">
                    <span className="frame-index">#{i + 1}</span>
                    <span className="frame-id">{frameId.substring(0, 8)}…</span>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </section>

      <section className="detail-section">
        <h2>Server Log</h2>
        <div className="log-panel">
          {logs.length === 0 ? (
            <p className="empty">No log messages yet. Logs stream in real-time when a job is running.</p>
          ) : (
            logs.map((entry, i) => (
              <div key={i} className={`log-entry log-${(entry.level || 'INFO').toLowerCase()}`}>
                <span className="log-time">
                  {new Date(entry.timestamp).toLocaleTimeString()}
                </span>
                <span className="log-level">[{entry.level}]</span>
                <span className="log-msg">{entry.message}</span>
              </div>
            ))
          )}
          <div ref={logEndRef} />
        </div>
      </section>
    </div>
  );
}
