import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { getAllJobs, createJob, startJob, pauseJob, cancelJob } from '../api/jobApi';
import { getAllCameras } from '../api/cameraApi';

const EMPTY_FORM = {
  camera_id: '',
  step_size: 10,
  max_frames: 50,
  motion_threshold: 0.02,
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
        ...form,
        step_size: Number(form.step_size),
        max_frames: Number(form.max_frames),
        motion_threshold: Number(form.motion_threshold),
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
    setForm((prev) => ({ ...prev, [e.target.name]: e.target.value }));
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
              name="camera_id"
              value={form.camera_id}
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
              name="step_size"
              type="number"
              min="1"
              value={form.step_size}
              onChange={handleChange}
            />
          </label>
          <label>
            Max Frames
            <input
              name="max_frames"
              type="number"
              min="1"
              value={form.max_frames}
              onChange={handleChange}
            />
          </label>
          <label>
            Motion Threshold
            <input
              name="motion_threshold"
              type="number"
              step="0.001"
              min="0"
              value={form.motion_threshold}
              onChange={handleChange}
            />
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
              <th>Created</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {jobs.map((job) => (
              <tr key={job.id}>
                <td>
                  <Link to={`/jobs/${job.id}`}>{job.id}</Link>
                </td>
                <td>{job.camera_id || job.camera_name || '—'}</td>
                <td>
                  <span className={`badge badge-${job.status || 'unknown'}`}>
                    {job.status || 'unknown'}
                  </span>
                </td>
                <td>{job.frames_captured ?? '—'}</td>
                <td>
                  {job.created_at
                    ? new Date(job.created_at).toLocaleString()
                    : '—'}
                </td>
                <td className="action-cell">
                  {(job.status === 'pending' || job.status === 'paused') && (
                    <button
                      className="btn btn-small btn-primary"
                      onClick={() => handleAction(job.id, startJob)}
                    >
                      Start
                    </button>
                  )}
                  {job.status === 'running' && (
                    <button
                      className="btn btn-small"
                      onClick={() => handleAction(job.id, pauseJob)}
                    >
                      Pause
                    </button>
                  )}
                  {(job.status === 'running' || job.status === 'paused') && (
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
