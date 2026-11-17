import { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import { request } from '../api/auth';
import { fetchSystemInfo } from '../api/systemApi';

function formatUptime(seconds) {
  const d = Math.floor(seconds / 86400);
  const h = Math.floor((seconds % 86400) / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  if (d > 0) return `${d}d ${h}h ${m}m`;
  if (h > 0) return `${h}h ${m}m`;
  return `${m}m`;
}

export default function StatusBar() {
  const [info, setInfo] = useState(null);

  const [fresnel, setFresnel] = useState('NOT_CONFIGURED');
  useEffect(() => {
    let mounted = true;
    async function pollPairing() {
      try {
        const result = await request('/fresnel/status');
        if (mounted) setFresnel(result.state);
      } catch {
        if (mounted) setFresnel(old => old === 'NOT_CONFIGURED' ? old : 'DISCONNECTED');
      }
    }
    pollPairing();
    const timer = setInterval(pollPairing, 10000);
    return () => { mounted = false; clearInterval(timer); };
  }, []);

  useEffect(() => {
    let active = true;
    async function poll() {
      try {
        const data = await fetchSystemInfo();
        if (active) setInfo(data);
      } catch {
        // ignore – will retry
      }
    }
    poll();
    const id = setInterval(poll, 10_000);
    return () => { active = false; clearInterval(id); };
  }, []);

  if (!info) return null;

  const mem = info.memory;
  const disk = info.disks?.[0];
  const connectedCams = info.cameras?.filter(c => c.connected) || [];

  return (
    <footer className="status-bar">
      {fresnel !== 'NOT_CONFIGURED' && <Link className="status-item" to="/integrations/fresnel">Fresnel · {fresnel}</Link>}
      <span className="status-item" title="Connected cameras">
        📷 {connectedCams.length > 0
          ? connectedCams.map(c => c.model || c.name).join(', ')
          : 'No camera'}
      </span>
      <span className="status-item" title="JVM memory (used / max)">
        💾 {mem.usedMb} / {mem.maxMb} MB
      </span>
      {disk && (
        <span className="status-item" title={`Disk ${disk.path}`}>
          🗄️ {disk.freeGb} GB free
        </span>
      )}
      <span className="status-item" title="Uptime">
        ⏱️ {formatUptime(info.uptimeSeconds)}
      </span>
    </footer>
  );
}
