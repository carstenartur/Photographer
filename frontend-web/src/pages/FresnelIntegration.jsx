import { useEffect, useState } from 'react';
import { request } from '../api/auth';

const admin = '/admin/integrations/fresnel';
const when = value => value ? new Date(value).toLocaleString() : 'Awaiting first request';

export default function FresnelIntegration() {
  const [data, setData] = useState(null);
  const [error, setError] = useState('');
  const [loadError, setLoadError] = useState('');
  const [baseUrl, setBaseUrl] = useState(window.location.origin);
  const [grant, setGrant] = useState(null);
  const [busy, setBusy] = useState(false);
  const [refresh, setRefresh] = useState(0);

  useEffect(() => {
    let mounted = true;
    async function load() {
      try {
        const result = await request(admin + '/pairings');
        if (mounted) { setData(result); setLoadError(''); }
      } catch (e) {
        if (mounted) setLoadError(e.status === 403 ? 'Administrator access is required.' : e.message);
      }
    }
    load();
    const timer = setInterval(load, 10000);
    return () => { mounted = false; clearInterval(timer); };
  }, [refresh]);

  useEffect(() => {
    if (!grant) return;
    const timeout = setTimeout(() => setGrant(null),
      Math.max(0, Date.parse(grant.expiresAt) - Date.now()));
    return () => clearTimeout(timeout);
  }, [grant]);

  async function issue(event) {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      setGrant(await request(admin + '/pairing-codes', {
        method: 'POST', body: JSON.stringify({ baseUrl }),
      }));
    } catch (e) { setError(e.message); }
    finally { setBusy(false); }
  }

  async function change(path, method) {
    setBusy(true);
    setError('');
    try {
      await request(admin + path, { method });
      setRefresh(n => n + 1);
    } catch (e) { setError(e.message); }
    finally { setBusy(false); }
  }

  return (
    <div className="page">
      <h1>Fresnel integration</h1>
      {(error || loadError) && <p role="alert" className="error">{error || loadError}</p>}
      {!data && !loadError && <p role="status">Loading pairings…</p>}
      {data && !data.enabled && <p>Set <code>focusstack.fresnel.enabled=true</code> and restart Photographer to enable pairing.</p>}
      {data?.enabled && <>
        <p>{data.descriptor.displayName} · {data.descriptor.applicationVersion} · protocol {data.descriptor.protocolVersion}</p>
        <form className="form-card" onSubmit={issue}>
          <label htmlFor="fresnel-origin">Photographer URL reachable from Fresnel</label>
          <input id="fresnel-origin" type="url" required value={baseUrl} onChange={e => setBaseUrl(e.target.value)} />
          <button className="btn btn-primary" disabled={busy}>Create pairing code</button>
          {grant && <div role="status">
            <p>Enter this code in Fresnel before {when(grant.expiresAt)}.</p>
            <code style={{ overflowWrap: 'anywhere' }}>{grant.pairingCode}</code>
            <button type="button" className="btn" onClick={() => setGrant(null)}>Hide code</button>
          </div>}
        </form>
        <h2>Paired instances</h2>
        {data.pairings.length === 0 && <p>No paired Fresnel instances.</p>}
        {data.pairings.map(({ pairing, sessions }) => (
          <section className="form-card" key={pairing.pairingId}>
            <h3>{pairing.displayName}</h3>
            <p><strong className={pairing.state === 'CONNECTED' && !loadError ? 'badge badge-completed' : 'badge'}>{loadError ? 'UNKNOWN' : pairing.state}</strong> · {pairing.applicationVersion}</p>
            <p>Paired {when(pairing.createdAt)} · Last authenticated request {when(pairing.lastSeenAt)}</p>
            <p>Scopes: {pairing.scopes.join(', ')}</p>
            {sessions.map(session => <div key={session.sessionId}>
              <p>Camera {session.cameraProfileId} · {session.state} · {session.completedSteps}/{session.totalSteps} steps
                {session.currentStepId && ' · ' + session.currentStepId}</p>
              <button className="btn" disabled={busy} onClick={() => change(
                '/pairings/' + pairing.pairingId + '/sessions/' + session.sessionId + '/abort', 'POST')}>Abort capture</button>
            </div>)}
            {pairing.state !== 'REVOKED' && <button className="btn btn-danger" disabled={busy} onClick={() => change(
              '/pairings/' + pairing.pairingId, 'DELETE')}>Revoke access</button>}
          </section>
        ))}
      </>}
    </div>
  );
}
