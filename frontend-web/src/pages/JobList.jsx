import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { getAllJobs, createJob, startJob, pauseJob, cancelJob } from '../api/jobApi';
import { getAllCameras } from '../api/cameraApi';

const EMPTY_FORM = {
  cameraProfileId: '',
  stepSize: 10,
  maxFrames: 50,
  abortThreshold: 0.02,
  motionDetectionEnabled: true,
  qualityMode: 'BALANCED',
};

export default function JobList() {
  const [searchParams] = useSearchParams();
  const [jobs, setJobs] = useState([]);
  const [cameras, setCameras] = useState([]);
  const [form, setForm] = useState(EMPTY_FORM);
  const [showForm, setShowForm] = useState(
    () => searchParams.get('new') === '1',
  );
  const [error, setError] = useState(null);

  useEffect(() => {
    let cancelled = false;
    getAllJobs()
      .then((data) => { if (!cancelled) setJobs(data || []); })
      .catch(() => { if (!cancelled) setError('Failed to load jobs.'); });
    getAllCameras()
      .then((data) => { if (!cancelled) setCameras(data || []); })
      .catch(() => { /* cameras list is optional */ });
    return () => { cancelled = true; };
  }, []);

  async function refreshJobs() {
    try {
      const data = await getAllJobs();
      setJobs(data || []);
    } catch {
      setError('Failed to load jobs.');
    }
  }

  async function handleCreate(e) {
    e.preventDefault();
    setError(null);
    try {
      await createJob({
        cameraProfileId: form.cameraProfileId,
        parameters: {
          startPosition: 0,
          stepSize: Number(form.stepSize),
          maxFrames: Number(form.maxFrames),
          abortThreshold: Number(form.abortThreshold),
          motionDetectionEnabled: form.motionDetectionEnabled,
          qualityMode: form.qualityMode,
        },
      });
      setForm(EMPTY_FORM);
      setShowForm(false);
      await refreshJobs();
    } catch (err) {
      setError(`Create failed: ${err.message}`);
    }
  }

  async function handleAction(id, action) {
    setError(null);
    try {
      await action(id);
      await refreshJobs();
    } catch (err) {
      setError(`Action failed: ${err.message}`);
    }
  }

  function handleChange(e) {
    const { name, value, type, checked } = e.target;
    setForm((prev) => ({ ...prev, [name]: type === 'checkbox' ? checked : value }));
  }

  return (
    <div className="page">
      <div className="page-header">
        <h1>Jobs</h1>
        <button
          className="btn btn-primary"
          onClick={() => setShowForm((v) => !v)}
        >
          {showForm ? 'Cancel' : 'Create Job'}
        </button>
      </div>

      {error && <p className="error">{error}</p>}

      {showForm && (
        <form className="form-card" onSubmit={handleCreate}>
          <h2>New Job</h2>
          <label>
            Camera Profile
            <select
              name="cameraProfileId"
              value={form.cameraProfileId}
              onChange={handleChange}
              required
            >
              <option value="">— Select camera —</option>
              {cameras.map((cam) => (
                <option key={cam.id} value={cam.id}>
                  {cam.name}
                </option>
              ))}
            </select>
          </label>
          <label>
            Step Size
            <input
              name="stepSize"
              type="number"
              min="1"
              value={form.stepSize}
              onChange={handleChange}
            />
          </label>
          <label>
            Max Frames
            <input
              name="maxFrames"
              type="number"
              min="1"
              value={form.maxFrames}
              onChange={handleChange}
            />
          </label>
          <label>
            Abort Threshold
            <input
              name="abortThreshold"
              type="number"
              step="0.001"
              min="0"
              value={form.abortThreshold}
              onChange={handleChange}
            />
          </label>
          <label>
            Quality Mode
            <select name="qualityMode" value={form.qualityMode} onChange={handleChange}>
              <option value="FAST">Fast</option>
              <option value="BALANCED">Balanced</option>
              <option value="MAXIMUM_QUALITY">Maximum Quality</option>
            </select>
          </label>
          <label className="checkbox-label">
            <input
              name="motionDetectionEnabled"
              type="checkbox"
              checked={form.motionDetectionEnabled}
              onChange={handleChange}
            />
            Enable Motion Detection
          </label>
          <button type="submit" className="btn btn-primary">
            Create
          </button>
        </form>
      )}

      {jobs.length === 0 ? (
        <p className="empty">No jobs yet.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>ID</th>
              <th>Camera</th>
              <th>Status</th>
              <th>Frames</th>
              <th>Started</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {jobs.map((job) => (
              <tr key={job.id}>
                <td>
                  <Link to={`/jobs/${job.id}`}>{job.id.substring(0, 8)}…</Link>
                </td>
                <td>{job.cameraProfileId || '—'}</td>
                <td>
                  <span className={`badge badge-${(job.status || 'UNKNOWN').toLowerCase()}`}>
                    {job.status || 'UNKNOWN'}
                  </span>
                </td>
                <td>{job.frames ? job.frames.length : 0}</td>
                <td>
                  {job.startedAt
                    ? new Date(job.startedAt).toLocaleString()
                    : '—'}
                </td>
                <td className="action-cell">
                  {(job.status === 'CREATED' || job.status === 'PAUSED') && (
                    <button
                      className="btn btn-small btn-primary"
                      onClick={() => handleAction(job.id, startJob)}
                    >
                      Start
                    </button>
                  )}
                  {job.status === 'RUNNING' && (
                    <button
                      className="btn btn-small"
                      onClick={() => handleAction(job.id, pauseJob)}
                    >
                      Pause
                    </button>
                  )}
                  {(job.status === 'RUNNING' || job.status === 'PAUSED') && (
                    <button
                      className="btn btn-small btn-danger"
                      onClick={() => handleAction(job.id, cancelJob)}
                    >
                      Cancel
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
