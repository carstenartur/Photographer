import { useEffect, useState, useRef, useCallback } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  getAllCameras,
  createCamera,
  updateCamera,
  deleteCamera,
  getCameraStatus,
  getCameraInfo,
  getLiveViewImage,
} from '../api/cameraApi';

const EMPTY_FORM = {
  name: '',
  backendType: 'mock',
  host: '',
  defaultStepSize: 10,
  maxFrames: 50,
  movementThreshold: 0.02,
};

/* ── small helper: battery icon + colour ── */
function BatteryIndicator({ level }) {
  if (level == null) return null;
  let colour = '#22c55e'; // green
  let icon = '🔋';
  if (level <= 20) { colour = '#ef4444'; icon = '🪫'; }
  else if (level <= 50) { colour = '#f59e0b'; }
  return (
    <span className="cam-battery" style={{ color: colour }}>
      {icon} {level}%
    </span>
  );
}

function ConnectionDot({ connected }) {
  return (
    <span
      className="cam-connection-dot"
      title={connected ? 'Connected' : 'Disconnected'}
      style={{ background: connected ? '#22c55e' : '#ef4444' }}
    />
  );
}

/* ── CameraCard: single camera with auto-refreshing liveview & info ── */
function CameraCard({ cam, onEdit, onDelete }) {
  const [status, setStatus] = useState(null);
  const [info, setInfo] = useState(null);
  const [liveviewSrc, setLiveviewSrc] = useState(null);
  const [lvError, setLvError] = useState(false);
  const unmounted = useRef(false);

  // Fetch info once
  useEffect(() => {
    unmounted.current = false;
    getCameraInfo(cam.id)
      .then((data) => { if (!unmounted.current) setInfo(data); })
      .catch(() => {});
    return () => { unmounted.current = true; };
  }, [cam.id]);

  // Fetch status on mount, then every 5 s
  useEffect(() => {
    let cancelled = false;
    const fetchStatus = () => {
      getCameraStatus(cam.id)
        .then((s) => { if (!cancelled) setStatus(s); })
        .catch(() => { if (!cancelled) setStatus(null); });
    };
    fetchStatus();
    const id = setInterval(fetchStatus, 5000);
    return () => { cancelled = true; clearInterval(id); };
  }, [cam.id]);

  // Poll liveview every 2 s
  useEffect(() => {
    let cancelled = false;
    const fetchLv = () => {
      getLiveViewImage(cam.id)
        .then((src) => {
          if (!cancelled) {
            setLiveviewSrc(src);
            setLvError(!src);
          }
        })
        .catch(() => { if (!cancelled) setLvError(true); });
    };
    fetchLv();
    const id = setInterval(fetchLv, 2000);
    return () => { cancelled = true; clearInterval(id); };
  }, [cam.id]);

  const connected = status?.connected ?? false;

  return (
    <div className="cam-card">
      {/* ── Header ── */}
      <div className="cam-card-header">
        <ConnectionDot connected={connected} />
        <h3>{cam.name}</h3>
        {status && <BatteryIndicator level={status.batteryLevel} />}
      </div>

      {/* ── Liveview ── */}
      <div className="cam-liveview">
        {liveviewSrc ? (
          <img src={liveviewSrc} alt={`${cam.name} live view`} className="cam-liveview-img" />
        ) : (
          <div className="cam-liveview-placeholder">
            {lvError ? 'No live view available' : 'Loading…'}
          </div>
        )}
      </div>

      {/* ── Info panel ── */}
      <div className="cam-info-grid">
        <div className="cam-info-row">
          <span className="cam-info-label">Model</span>
          <span className="cam-info-value">{info?.model || cam.backendType}</span>
        </div>
        <div className="cam-info-row">
          <span className="cam-info-label">Firmware</span>
          <span className="cam-info-value">{info?.firmwareVersion || '—'}</span>
        </div>
        {cam.host && (
          <div className="cam-info-row">
            <span className="cam-info-label">{cam.backendType === 'folder' ? 'Path' : 'Host'}</span>
            <span className="cam-info-value">{cam.host}</span>
          </div>
        )}
        {status && (
          <>
            <div className="cam-info-row">
              <span className="cam-info-label">Storage</span>
              <span className="cam-info-value">
                {status.storageAvailable != null
                  ? `${(status.storageAvailable / 1_000_000).toFixed(1)} MB`
                  : '—'}
              </span>
            </div>
            <div className="cam-info-row">
              <span className="cam-info-label">Exposure</span>
              <span className="cam-info-value">
                {status.shutterSpeed || '—'} &nbsp;{status.aperture || ''} &nbsp;{status.iso || ''}
              </span>
            </div>
            <div className="cam-info-row">
              <span className="cam-info-label">Focus Pos</span>
              <span className="cam-info-value">{status.currentFocusPosition ?? '—'}</span>
            </div>
          </>
        )}
      </div>

      {/* ── Actions ── */}
      <div className="cam-card-actions">
        <span className="cam-type-badge">{cam.backendType}</span>
        <button className="btn btn-small" onClick={() => onEdit(cam)}>Edit</button>
        <button className="btn btn-small btn-danger" onClick={() => onDelete(cam.id)}>
          Delete
        </button>
      </div>
    </div>
  );
}

/* ── Main page component ── */
export default function CameraList() {
  const [searchParams] = useSearchParams();
  const [cameras, setCameras] = useState([]);
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

  const refreshCameras = useCallback(async () => {
    try {
      const data = await getAllCameras();
      setCameras(data || []);
    } catch {
      setError('Failed to load cameras.');
    }
  }, []);

  async function handleSave(e) {
    e.preventDefault();
    setError(null);
    try {
      const profile = {
        id: form.id || crypto.randomUUID(),
        name: form.name,
        backendType: form.backendType,
        host: ['canon-ccapi', 'folder'].includes(form.backendType) ? form.host : '',
        defaultStepSize: Number(form.defaultStepSize),
        maxFrames: Number(form.maxFrames),
        movementThreshold: Number(form.movementThreshold),
      };
      if (form.id) await updateCamera(form.id, profile);
      else await createCamera(profile);
      setForm(EMPTY_FORM);
      setShowForm(false);
      await refreshCameras();
    } catch (err) {
      setError(`${form.id ? 'Update' : 'Create'} failed: ${err.message}`);
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

  function handleChange(e) {
    setForm((prev) => ({ ...prev, [e.target.name]: e.target.value }));
  }

  return (
    <div className="page">
      <div className="page-header">
        <h1>Cameras</h1>
        <button
          className="btn btn-primary"
          onClick={() => { setForm(EMPTY_FORM); setShowForm(!showForm); setError(null); }}
        >
          {showForm ? 'Cancel' : 'Add Camera'}
        </button>
      </div>

      {error && <p className="error">{error}</p>}

      {showForm && (
        <form className="form-card" onSubmit={handleSave}>
          <h2>{form.id ? 'Edit Camera Profile' : 'New Camera Profile'}</h2>
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
              <option value="canon-usb">Canon USB (PTP)</option>
              <option value="folder">Folder</option>
            </select>
          </label>
          {form.backendType === 'canon-ccapi' && (
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
          )}
          {form.backendType === 'folder' && (
            <label>
              Folder Path
              <input
                name="host"
                value={form.host}
                onChange={handleChange}
                placeholder="/path/to/image/folder"
                required
              />
            </label>
          )}
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
            {form.id ? 'Save' : 'Create'}
          </button>
        </form>
      )}

      {cameras.length === 0 ? (
        <p className="empty">No camera profiles configured.</p>
      ) : (
        <div className="cam-grid">
          {cameras.map((cam) => (
            <CameraCard key={cam.id} cam={cam} onDelete={handleDelete}
              onEdit={(profile) => { setForm({ ...profile, host: profile.host ?? '' }); setShowForm(true); setError(null); }} />
          ))}
        </div>
      )}
    </div>
  );
}
