import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { getAllJobs, createJob, startJob, pauseJob, cancelJob } from '../api/jobApi';
import { getAllCameras, getCameraStatus } from '../api/cameraApi';

const EMPTY_FORM = {
  cameraProfileId: '',
  stepSize: 10,
  maxFrames: 50,
  abortThreshold: 0.02,
  motionDetectionEnabled: true,
  qualityMode: 'BALANCED',
};

/**
 * Quality preset definitions.
 * Each preset pre-fills the form fields for a specific capture style.
 */
const PRESETS = {
  FAST: {
    label: '⚡ Fast',
    stepSize: 20,
    maxFrames: 15,
    abortThreshold: 0.05,
    motionDetectionEnabled: true,
    qualityMode: 'FAST',
  },
  BALANCED: {
    label: '⚖️ Balanced',
    stepSize: 10,
    maxFrames: 50,
    abortThreshold: 0.02,
    motionDetectionEnabled: true,
    qualityMode: 'BALANCED',
  },
  MAXIMUM_QUALITY: {
    label: '🔬 Max Quality',
    stepSize: 3,
    maxFrames: 120,
    abortThreshold: 0.005,
    motionDetectionEnabled: true,
    qualityMode: 'MAXIMUM_QUALITY',
  },
};

/** Sort jobs so the newest (most recently started) appears first. */
function sortNewestFirst(list) {
  return [...list].sort((a, b) => {
    const ta = a.startedAt ? new Date(a.startedAt).getTime() : Infinity;
    const tb = b.startedAt ? new Date(b.startedAt).getTime() : Infinity;
    return tb - ta;
  });
}

export default function JobList() {
  const [searchParams] = useSearchParams();
  const [jobs, setJobs] = useState([]);
  const [cameras, setCameras] = useState([]);
  const [form, setForm] = useState(EMPTY_FORM);
  const [showForm, setShowForm] = useState(
    () => searchParams.get('new') === '1',
  );
  const [error, setError] = useState(null);
  const [batteryLevels, setBatteryLevels] = useState({});

  useEffect(() => {
    let cancelled = false;
    getAllJobs()
      .then((data) => { if (!cancelled) setJobs(sortNewestFirst(data || [])); })
      .catch(() => { if (!cancelled) setError('Failed to load jobs.'); });
    getAllCameras()
      .then((data) => { if (!cancelled) setCameras(data || []); })
      .catch(() => { /* cameras list is optional */ });
    return () => { cancelled = true; };
  }, []);

  async function refreshJobs() {
    try {
      const data = await getAllJobs();
      setJobs(sortNewestFirst(data || []));
    } catch {
      setError('Failed to load jobs.');
    }
  }

  // Fetch battery levels for cameras used by active jobs
  useEffect(() => {
    const activeProfiles = [...new Set(
      jobs
        .filter((j) => j.status === 'RUNNING' || j.status === 'CREATED' || j.status === 'PAUSED')
        .map((j) => j.cameraProfileId)
        .filter(Boolean),
    )];
    if (activeProfiles.length === 0) return;
    let cancelled = false;
    async function fetchBatteries() {
      const levels = {};
      await Promise.all(activeProfiles.map(async (profileId) => {
        try {
          const status = await getCameraStatus(profileId);
          levels[profileId] = status.batteryLevel;
        } catch { /* best-effort */ }
      }));
      if (!cancelled) setBatteryLevels((prev) => ({ ...prev, ...levels }));
    }
    fetchBatteries();
    const interval = setInterval(fetchBatteries, 15000);
    return () => { cancelled = true; clearInterval(interval); };
  }, [jobs]);

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

  function applyPreset(key) {
    const preset = PRESETS[key];
    if (!preset) return;
    setForm((prev) => ({
      ...prev,
      stepSize: preset.stepSize,
      maxFrames: preset.maxFrames,
      abortThreshold: preset.abortThreshold,
      motionDetectionEnabled: preset.motionDetectionEnabled,
      qualityMode: preset.qualityMode,
    }));
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

          {/* Quality presets */}
          <div style={{ marginBottom: '1rem' }}>
            <p style={{ margin: '0 0 0.5rem', fontSize: '0.875rem', color: '#94a3b8' }}>
              Presets
            </p>
            <div style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap' }}>
              {Object.entries(PRESETS).map(([key, preset]) => (
                <button
                  key={key}
                  type="button"
                  className={`btn btn-small ${form.qualityMode === key ? 'btn-primary' : 'btn-secondary'}`}
                  onClick={() => applyPreset(key)}
                >
                  {preset.label}
                </button>
              ))}
            </div>
          </div>

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
                  {(job.status === 'RUNNING' || job.status === 'CREATED' || job.status === 'PAUSED')
                    && batteryLevels[job.cameraProfileId] != null && (
                    <span
                      className="job-battery"
                      style={{
                        color: batteryLevels[job.cameraProfileId] <= 15 ? '#ef4444'
                          : batteryLevels[job.cameraProfileId] <= 30 ? '#f59e0b' : '#22c55e',
                      }}
                      title={`Camera battery: ${batteryLevels[job.cameraProfileId]}%`}
                    >
                      🔋 {batteryLevels[job.cameraProfileId]}%
                    </span>
                  )}
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
