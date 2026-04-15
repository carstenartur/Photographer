import { useEffect, useState, useRef } from 'react';
import {
  listPlugins,
  installPlugin,
  enablePlugin,
  disablePlugin,
  uninstallPlugin,
} from '../api/pluginApi';

export default function PluginManager() {
  const [plugins, setPlugins] = useState([]);
  const [error, setError] = useState(null);
  const [success, setSuccess] = useState(null);
  const [uploading, setUploading] = useState(false);
  const fileInputRef = useRef(null);

  useEffect(() => {
    let cancelled = false;
    async function load() {
      try {
        const data = await listPlugins();
        if (!cancelled) setPlugins(data || []);
      } catch (err) {
        if (!cancelled) setError(`Failed to load plugins: ${err.message}`);
      }
    }
    load();
    return () => { cancelled = true; };
  }, []);

  async function refreshPlugins() {
    setError(null);
    try {
      const data = await listPlugins();
      setPlugins(data || []);
    } catch (err) {
      setError(`Failed to load plugins: ${err.message}`);
    }
  }

  async function handleInstall(e) {
    const file = e.target.files?.[0];
    if (!file) return;

    if (!file.name.toLowerCase().endsWith('.jar')) {
      setError('Only .jar plugin files are accepted.');
      return;
    }

    setError(null);
    setSuccess(null);
    setUploading(true);
    try {
      const result = await installPlugin(file);
      setSuccess(`Plugin "${result.pluginId}" installed successfully.`);
      await refreshPlugins();
    } catch (err) {
      setError(`Install failed: ${err.message}`);
    } finally {
      setUploading(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  }

  async function handleToggle(id, currentState) {
    setError(null);
    setSuccess(null);
    try {
      if (currentState === 'ENABLED') {
        await disablePlugin(id);
        setSuccess(`Plugin "${id}" disabled.`);
      } else {
        await enablePlugin(id);
        setSuccess(`Plugin "${id}" enabled.`);
      }
      await refreshPlugins();
    } catch (err) {
      setError(`Action failed: ${err.message}`);
    }
  }

  async function handleUninstall(id) {
    if (!window.confirm(`Are you sure you want to uninstall plugin "${id}"? This will remove the plugin JAR and cannot be undone.`)) {
      return;
    }
    setError(null);
    setSuccess(null);
    try {
      await uninstallPlugin(id);
      setSuccess(`Plugin "${id}" uninstalled.`);
      await refreshPlugins();
    } catch (err) {
      setError(`Uninstall failed: ${err.message}`);
    }
  }

  return (
    <div className="page">
      <div className="page-header">
        <h1>Plugins</h1>
        <label className="btn btn-primary" style={{ cursor: 'pointer', position: 'relative' }}>
          {uploading ? 'Installing…' : 'Install Plugin'}
          <input
            ref={fileInputRef}
            type="file"
            accept=".jar"
            onChange={handleInstall}
            disabled={uploading}
            style={{ display: 'none' }}
          />
        </label>
      </div>

      {error && <p className="error">{error}</p>}
      {success && <p style={{ color: '#22c55e', margin: '0.5rem 0' }}>{success}</p>}

      <p style={{ color: '#94a3b8', fontSize: '0.875rem', margin: '0.5rem 0 1rem' }}>
        Install external plugins (.jar files) to extend Focus Stack with new camera backends,
        AI vision providers, or analysis algorithms. Plugins are loaded in isolated classloaders
        and can be enabled, disabled, or uninstalled at any time.
      </p>

      {plugins.length === 0 ? (
        <div className="form-card" style={{ textAlign: 'center', padding: '2rem' }}>
          <p style={{ fontSize: '2rem', margin: '0 0 0.5rem' }}>🧩</p>
          <p style={{ margin: '0 0 0.5rem', fontWeight: 600 }}>No plugins installed</p>
          <p style={{ color: '#94a3b8', fontSize: '0.875rem' }}>
            Click &quot;Install Plugin&quot; to upload a .jar plugin file.
          </p>
        </div>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Plugin ID</th>
              <th>Version</th>
              <th>Description</th>
              <th>Provider</th>
              <th>License</th>
              <th>State</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {plugins.map((plugin) => (
              <tr key={plugin.id}>
                <td style={{ fontWeight: 600 }}>{plugin.id}</td>
                <td>{plugin.version || '—'}</td>
                <td>{plugin.description || '—'}</td>
                <td>{plugin.provider || '—'}</td>
                <td>{plugin.license || '—'}</td>
                <td>
                  <span className={`badge badge-${plugin.state === 'ENABLED' ? 'completed' : 'cancelled'}`}>
                    {plugin.state}
                  </span>
                </td>
                <td className="action-cell">
                  <button
                    className={`btn btn-small ${plugin.state === 'ENABLED' ? '' : 'btn-primary'}`}
                    onClick={() => handleToggle(plugin.id, plugin.state)}
                  >
                    {plugin.state === 'ENABLED' ? 'Disable' : 'Enable'}
                  </button>
                  <button
                    className="btn btn-small btn-danger"
                    onClick={() => handleUninstall(plugin.id)}
                  >
                    Uninstall
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
