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
  backendType: 'mock',
  host: '',
  defaultStepSize: 10,
  maxFrames: 50,
  movementThreshold: 0.02,
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
        id: form.id || crypto.randomUUID(),
        name: form.name,
        backendType: form.backendType,
        host: form.host,
        defaultStepSize: Number(form.defaultStepSize),
        maxFrames: Number(form.maxFrames),
        movementThreshold: Number(form.movementThreshold),
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
              name="backendType"
              value={form.backendType}
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
              required
            />
          </label>
          <label>
            Step Size
            <input
              name="defaultStepSize"
              type="number"
              min="1"
              value={form.defaultStepSize}
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
            Motion Threshold
            <input
              name="movementThreshold"
              type="number"
              step="0.001"
              min="0"
              value={form.movementThreshold}
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
                <strong>Type:</strong> {cam.backendType}
              </p>
              <p>
                <strong>Host:</strong> {cam.host || '—'}
              </p>
              <p>
                <strong>Step Size:</strong> {cam.defaultStepSize}
              </p>
              <p>
                <strong>Max Frames:</strong> {cam.maxFrames}
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
