import { useEffect, useState, useRef } from 'react';
import { useParams, Link } from 'react-router-dom';
import { getJob, startJob, pauseJob, cancelJob } from '../api/jobApi';
import PreviewPanel from '../components/PreviewPanel';

export default function JobDetail() {
  const { id } = useParams();
  const [job, setJob] = useState(null);
  const [error, setError] = useState(null);
  const [logs, setLogs] = useState([]);
  const logEndRef = useRef(null);

  useEffect(() => {
    let cancelled = false;
    getJob(id)
      .then((data) => { if (!cancelled) setJob(data); })
      .catch(() => { if (!cancelled) setError('Failed to load job.'); });
    return () => { cancelled = true; };
  }, [id]);

  // Auto-refresh while job is RUNNING
  useEffect(() => {
    if (!job || (job.status !== 'RUNNING' && job.status !== 'CREATED')) return;
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
  }, [id, job?.status]);

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
