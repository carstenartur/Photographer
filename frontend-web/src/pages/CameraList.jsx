import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  getAllCameras,
  createCamera,
  deleteCamera,
  getCameraStatus,
} from '../api/cameraApi';

const EMPTY_FORM = {
  name: '',
  backend_type: 'mock',
  host: '',
  step_size: 10,
  max_frames: 50,
  motion_threshold: 0.02,
};

export default function CameraList() {
  const [searchParams] = useSearchParams();
  const [cameras, setCameras] = useState([]);
  const [statuses, setStatuses] = useState({});
  const [form, setForm] = useState(EMPTY_FORM);
  const [showForm, setShowForm] = useState(
    () => searchParams.get('new') === '1',
  );
  const [error, setError] = useState(null);

  useEffect(() => {
    let cancelled = false;
    getAllCameras()
      .then((data) => { if (!cancelled) setCameras(data || []); })
      .catch(() => { if (!cancelled) setError('Failed to load cameras.'); });
    return () => { cancelled = true; };
  }, []);

  async function refreshCameras() {
    try {
      const data = await getAllCameras();
      setCameras(data || []);
    } catch {
      setError('Failed to load cameras.');
    }
  }

  async function handleCreate(e) {
    e.preventDefault();
    setError(null);
    try {
      await createCamera({
        ...form,
        step_size: Number(form.step_size),
        max_frames: Number(form.max_frames),
        motion_threshold: Number(form.motion_threshold),
      });
      setForm(EMPTY_FORM);
      setShowForm(false);
      await refreshCameras();
    } catch (err) {
      setError(`Create failed: ${err.message}`);
    }
  }

  async function handleDelete(id) {
    if (!window.confirm('Delete this camera profile?')) return;
    try {
      await deleteCamera(id);
      await refreshCameras();
    } catch (err) {
      setError(`Delete failed: ${err.message}`);
    }
  }

  async function handleStatus(id) {
    try {
      const status = await getCameraStatus(id);
      setStatuses((prev) => ({ ...prev, [id]: status }));
    } catch {
      setStatuses((prev) => ({
        ...prev,
        [id]: { error: 'Unable to fetch status' },
      }));
    }
  }

  function handleChange(e) {
    setForm((prev) => ({ ...prev, [e.target.name]: e.target.value }));
  }

  return (
    <div className="page">
      <div className="page-header">
        <h1>Cameras</h1>
        <button
          className="btn btn-primary"
          onClick={() => setShowForm((v) => !v)}
        >
          {showForm ? 'Cancel' : 'Add Camera'}
        </button>
      </div>

      {error && <p className="error">{error}</p>}

      {showForm && (
        <form className="form-card" onSubmit={handleCreate}>
          <h2>New Camera Profile</h2>
          <label>
            Name
            <input
              name="name"
              value={form.name}
              onChange={handleChange}
              required
            />
          </label>
          <label>
            Backend Type
            <select
              name="backend_type"
              value={form.backend_type}
              onChange={handleChange}
            >
              <option value="mock">Mock</option>
              <option value="canon-ccapi">Canon CCAPI</option>
            </select>
          </label>
          <label>
            Host IP
            <input
              name="host"
              value={form.host}
              onChange={handleChange}
              placeholder="192.168.1.100"
            />
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

      {cameras.length === 0 ? (
        <p className="empty">No camera profiles configured.</p>
      ) : (
        <div className="card-grid">
          {cameras.map((cam) => (
            <div key={cam.id} className="card">
              <h3>{cam.name}</h3>
              <p>
                <strong>Type:</strong> {cam.backend_type}
              </p>
              <p>
                <strong>Host:</strong> {cam.host || '—'}
              </p>
              <p>
                <strong>Step Size:</strong> {cam.step_size}
              </p>
              <p>
                <strong>Max Frames:</strong> {cam.max_frames}
              </p>
              <div className="card-actions">
                <button
                  className="btn btn-small"
                  onClick={() => handleStatus(cam.id)}
                >
                  Status
                </button>
                <button
                  className="btn btn-small btn-danger"
                  onClick={() => handleDelete(cam.id)}
                >
                  Delete
                </button>
              </div>
              {statuses[cam.id] && (
                <pre className="status-block">
                  {JSON.stringify(statuses[cam.id], null, 2)}
                </pre>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
