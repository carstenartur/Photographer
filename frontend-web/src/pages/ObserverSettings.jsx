import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  getStatus,
  getProfiles,
  getAiProviders,
  updateConfig,
  createProfile,
  updateProfile,
  testAiProvider,
  exportEvents,
  exportSpecies,
} from '../api/observerApi';

export default function ObserverSettings() {
  const [, setStatus] = useState(null);
  const [profiles, setProfiles] = useState([]);
  const [aiProviders, setAiProviders] = useState([]);
  const [error, setError] = useState(null);
  const [success, setSuccess] = useState(null);
  const [loading, setLoading] = useState(true);

  // Config form state
  const [configForm, setConfigForm] = useState({
    enabled: false,
    defaultProfile: '',
    preferredAiProvider: '',
  });

  // Profile form state
  const [showProfileForm, setShowProfileForm] = useState(false);
  const [editingProfile, setEditingProfile] = useState(null);
  const [profileForm, setProfileForm] = useState({
    name: '',
    description: '',
    captureIntervalSeconds: 30,
    motionSensitivity: 0.5,
    minimumConfidence: 0.7,
  });

  // AI test state
  const [testingProvider, setTestingProvider] = useState(null);
  const [testResult, setTestResult] = useState(null);

  useEffect(() => {
    let cancelled = false;
    async function load() {
      try {
        const [statusData, profilesData, providersData] = await Promise.all([
          getStatus(),
          getProfiles(),
          getAiProviders(),
        ]);
        if (cancelled) return;
        setStatus(statusData);
        setProfiles(profilesData || []);
        setAiProviders(providersData || []);
        if (statusData) {
          setConfigForm({
            enabled: statusData.enabled ?? false,
            defaultProfile: statusData.defaultProfile ?? '',
            preferredAiProvider: statusData.preferredAiProvider ?? '',
          });
        }
      } catch (err) {
        if (!cancelled) setError(`Failed to load settings: ${err.message}`);
      } finally {
        if (!cancelled) setLoading(false);
      }
    }
    load();
    return () => { cancelled = true; };
  }, []);

  async function handleConfigSave(e) {
    e.preventDefault();
    setError(null);
    setSuccess(null);
    try {
      await updateConfig(configForm);
      setSuccess('Configuration updated successfully.');
      const statusData = await getStatus();
      setStatus(statusData);
    } catch (err) {
      setError(`Save failed: ${err.message}`);
    }
  }

  function handleConfigChange(e) {
    const { name, value, type, checked } = e.target;
    setConfigForm((prev) => ({
      ...prev,
      [name]: type === 'checkbox' ? checked : value,
    }));
  }

  function startCreateProfile() {
    setEditingProfile(null);
    setProfileForm({
      name: '',
      description: '',
      captureIntervalSeconds: 30,
      motionSensitivity: 0.5,
      minimumConfidence: 0.7,
    });
    setShowProfileForm(true);
  }

  function startEditProfile(profile) {
    setEditingProfile(profile.name);
    setProfileForm({
      name: profile.name,
      description: profile.description || '',
      captureIntervalSeconds: profile.captureIntervalSeconds ?? 30,
      motionSensitivity: profile.motionSensitivity ?? 0.5,
      minimumConfidence: profile.minimumConfidence ?? 0.7,
    });
    setShowProfileForm(true);
  }

  async function handleProfileSave(e) {
    e.preventDefault();
    setError(null);
    setSuccess(null);
    try {
      if (editingProfile) {
        await updateProfile(editingProfile, profileForm);
        setSuccess(`Profile "${editingProfile}" updated.`);
      } else {
        await createProfile(profileForm);
        setSuccess(`Profile "${profileForm.name}" created.`);
      }
      setShowProfileForm(false);
      const data = await getProfiles();
      setProfiles(data || []);
    } catch (err) {
      setError(`Profile save failed: ${err.message}`);
    }
  }

  function handleProfileChange(e) {
    const { name, value } = e.target;
    setProfileForm((prev) => ({ ...prev, [name]: value }));
  }

  async function handleTestAi(providerName) {
    setTestingProvider(providerName);
    setTestResult(null);
    setError(null);
    try {
      const result = await testAiProvider({ provider: providerName });
      setTestResult({ provider: providerName, ...result });
    } catch (err) {
      setTestResult({ provider: providerName, error: err.message });
    } finally {
      setTestingProvider(null);
    }
  }

  async function handleExport(type, format) {
    setError(null);
    try {
      const blob = type === 'events'
        ? await exportEvents(format)
        : await exportSpecies(format);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `observer-${type}.${format}`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
    } catch (err) {
      setError(`Export failed: ${err.message}`);
    }
  }

  if (loading) {
    return <div className="page"><p>Loading settings…</p></div>;
  }

  return (
    <div className="page">
      <div className="page-header">
        <h1>Observer Settings</h1>
        <Link to="/observer" className="btn">← Back to Observer</Link>
      </div>

      {error && <p className="error">{error}</p>}
      {success && <p style={{ color: '#22c55e', margin: '0.5rem 0' }}>{success}</p>}

      {/* ── General Configuration ── */}
      <section className="form-card">
        <h2>General Configuration</h2>
        <form onSubmit={handleConfigSave}>
          <label className="checkbox-label">
            <input
              name="enabled"
              type="checkbox"
              checked={configForm.enabled}
              onChange={handleConfigChange}
            />
            Observer Enabled
          </label>
          <label>
            Default Profile
            <select name="defaultProfile" value={configForm.defaultProfile} onChange={handleConfigChange}>
              <option value="">— Select —</option>
              {profiles.map((p) => (
                <option key={p.name} value={p.name}>{p.name}</option>
              ))}
            </select>
          </label>
          <label>
            Preferred AI Provider
            <select name="preferredAiProvider" value={configForm.preferredAiProvider} onChange={handleConfigChange}>
              <option value="">— Select —</option>
              {aiProviders.map((p) => (
                <option key={p.name} value={p.name}>{p.name}</option>
              ))}
            </select>
          </label>
          <button type="submit" className="btn btn-primary">Save Configuration</button>
        </form>
      </section>

      {/* ── Observer Profiles ── */}
      <section style={{ marginTop: '2rem' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
          <h2>Observer Profiles</h2>
          <button className="btn btn-primary" onClick={startCreateProfile}>
            New Profile
          </button>
        </div>

        {showProfileForm && (
          <form className="form-card" onSubmit={handleProfileSave} style={{ marginBottom: '1rem' }}>
            <h3>{editingProfile ? `Edit Profile: ${editingProfile}` : 'New Profile'}</h3>
            <label>
              Name
              <input
                name="name"
                value={profileForm.name}
                onChange={handleProfileChange}
                required
                disabled={!!editingProfile}
              />
            </label>
            <label>
              Description
              <input name="description" value={profileForm.description} onChange={handleProfileChange} />
            </label>
            <label>
              Capture Interval (seconds)
              <input
                name="captureIntervalSeconds"
                type="number"
                min="1"
                value={profileForm.captureIntervalSeconds}
                onChange={handleProfileChange}
              />
            </label>
            <label>
              Motion Sensitivity (0–1)
              <input
                name="motionSensitivity"
                type="number"
                step="0.01"
                min="0"
                max="1"
                value={profileForm.motionSensitivity}
                onChange={handleProfileChange}
              />
            </label>
            <label>
              Minimum Confidence (0–1)
              <input
                name="minimumConfidence"
                type="number"
                step="0.01"
                min="0"
                max="1"
                value={profileForm.minimumConfidence}
                onChange={handleProfileChange}
              />
            </label>
            <div style={{ display: 'flex', gap: '0.5rem' }}>
              <button type="submit" className="btn btn-primary">
                {editingProfile ? 'Update Profile' : 'Create Profile'}
              </button>
              <button type="button" className="btn" onClick={() => setShowProfileForm(false)}>
                Cancel
              </button>
            </div>
          </form>
        )}

        {profiles.length === 0 ? (
          <p className="empty">No profiles configured.</p>
        ) : (
          <table>
            <thead>
              <tr>
                <th>Name</th>
                <th>Description</th>
                <th>Interval</th>
                <th>Sensitivity</th>
                <th>Min Confidence</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {profiles.map((p) => (
                <tr key={p.name}>
                  <td style={{ fontWeight: 600 }}>
                    {p.name}
                    {p.name === configForm.defaultProfile && (
                      <span className="badge badge-completed" style={{ marginLeft: '0.5rem', fontSize: '0.7rem' }}>
                        DEFAULT
                      </span>
                    )}
                  </td>
                  <td>{p.description || '—'}</td>
                  <td>{p.captureIntervalSeconds ?? '—'}s</td>
                  <td>{p.motionSensitivity ?? '—'}</td>
                  <td>{p.minimumConfidence ?? '—'}</td>
                  <td>
                    <button className="btn btn-small" onClick={() => startEditProfile(p)}>
                      Edit
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      {/* ── AI Providers ── */}
      <section style={{ marginTop: '2rem' }}>
        <h2>AI Providers</h2>
        {aiProviders.length === 0 ? (
          <p className="empty">No AI providers available.</p>
        ) : (
          <div className="status-cards">
            {aiProviders.map((p) => (
              <div key={p.name} className="card">
                <h3>{p.name}</h3>
                <p className="card-value">
                  <span className={`badge badge-${p.configured ? 'completed' : 'idle'}`}>
                    {p.configured ? 'Configured' : 'Not Configured'}
                  </span>
                </p>
                <p className="card-label">Model: {p.model || '—'}</p>
                <button
                  className="btn btn-small"
                  onClick={() => handleTestAi(p.name)}
                  disabled={testingProvider === p.name}
                  style={{ marginTop: '0.5rem' }}
                >
                  {testingProvider === p.name ? 'Testing…' : 'Test Connection'}
                </button>
                {testResult && testResult.provider === p.name && (
                  <p style={{ fontSize: '0.8rem', marginTop: '0.25rem', color: testResult.error ? '#ef4444' : '#22c55e' }}>
                    {testResult.error ? `❌ ${testResult.error}` : '✅ Connection successful'}
                  </p>
                )}
              </div>
            ))}
          </div>
        )}
      </section>

      {/* ── Data Export ── */}
      <section style={{ marginTop: '2rem' }}>
        <h2>Data Export</h2>
        <p style={{ color: '#94a3b8', fontSize: '0.875rem', marginBottom: '1rem' }}>
          Export observation data for backup or external analysis.
        </p>
        <div style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap' }}>
          <button className="btn" onClick={() => handleExport('events', 'json')}>
            Export Events (JSON)
          </button>
          <button className="btn" onClick={() => handleExport('events', 'csv')}>
            Export Events (CSV)
          </button>
          <button className="btn" onClick={() => handleExport('species', 'json')}>
            Export Species (JSON)
          </button>
          <button className="btn" onClick={() => handleExport('species', 'csv')}>
            Export Species (CSV)
          </button>
        </div>
      </section>
    </div>
  );
}
