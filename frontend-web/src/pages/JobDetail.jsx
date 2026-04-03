import { useEffect, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { getJob, startJob, pauseJob, cancelJob } from '../api/jobApi';

export default function JobDetail() {
  const { id } = useParams();
  const [job, setJob] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    let cancelled = false;
    getJob(id)
      .then((data) => { if (!cancelled) setJob(data); })
      .catch(() => { if (!cancelled) setError('Failed to load job.'); });
    return () => { cancelled = true; };
  }, [id]);

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
  const analysis = job.analysis || [];

  return (
    <div className="page">
      <Link to="/jobs" className="back-link">
        ← Back to Jobs
      </Link>

      <div className="page-header">
        <h1>Job {job.id}</h1>
        <span className={`badge badge-${job.status || 'unknown'}`}>
          {job.status || 'unknown'}
        </span>
      </div>

      {error && <p className="error">{error}</p>}

      <section className="detail-actions">
        {(job.status === 'pending' || job.status === 'paused') && (
          <button
            className="btn btn-primary"
            onClick={() => handleAction(startJob)}
          >
            Start
          </button>
        )}
        {job.status === 'running' && (
          <button className="btn" onClick={() => handleAction(pauseJob)}>
            Pause
          </button>
        )}
        {(job.status === 'running' || job.status === 'paused') && (
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
            style={{
              width: `${job.max_frames ? Math.round(((job.frames_captured || 0) / job.max_frames) * 100) : 0}%`,
            }}
          />
        </div>
        <p>
          {job.frames_captured ?? 0} / {job.max_frames ?? '?'} frames captured
        </p>
      </section>

      <section className="detail-section">
        <h2>Parameters</h2>
        <dl className="params">
          <dt>Camera</dt>
          <dd>{job.camera_id || job.camera_name || '—'}</dd>
          <dt>Step Size</dt>
          <dd>{job.step_size ?? '—'}</dd>
          <dt>Max Frames</dt>
          <dd>{job.max_frames ?? '—'}</dd>
          <dt>Motion Threshold</dt>
          <dd>{job.motion_threshold ?? '—'}</dd>
          <dt>Created</dt>
          <dd>
            {job.created_at
              ? new Date(job.created_at).toLocaleString()
              : '—'}
          </dd>
        </dl>
      </section>

      <section className="detail-section">
        <h2>Captured Frames ({frames.length})</h2>
        {frames.length === 0 ? (
          <p className="empty">No frames captured yet.</p>
        ) : (
          <table>
            <thead>
              <tr>
                <th>#</th>
                <th>Filename</th>
                <th>Focus Position</th>
                <th>Captured At</th>
              </tr>
            </thead>
            <tbody>
              {frames.map((frame, i) => (
                <tr key={frame.id || i}>
                  <td>{i + 1}</td>
                  <td>{frame.filename || frame.path || '—'}</td>
                  <td>{frame.focus_position ?? '—'}</td>
                  <td>
                    {frame.captured_at
                      ? new Date(frame.captured_at).toLocaleString()
                      : '—'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <section className="detail-section">
        <h2>Analysis Results ({analysis.length})</h2>
        {analysis.length === 0 ? (
          <p className="empty">No analysis results yet.</p>
        ) : (
          <table>
            <thead>
              <tr>
                <th>Frame</th>
                <th>Sharpness</th>
                <th>Contrast</th>
                <th>Motion Detected</th>
              </tr>
            </thead>
            <tbody>
              {analysis.map((a, i) => (
                <tr key={a.id || i}>
                  <td>{a.frame_index ?? i + 1}</td>
                  <td>{a.sharpness?.toFixed(4) ?? '—'}</td>
                  <td>{a.contrast?.toFixed(4) ?? '—'}</td>
                  <td>{a.motion_detected != null ? String(a.motion_detected) : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </div>
  );
}
