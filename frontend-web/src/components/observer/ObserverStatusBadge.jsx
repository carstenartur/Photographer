/**
 * Shows the current observer pipeline status as a badge.
 */
export default function ObserverStatusBadge({ status }) {
  if (!status) {
    return <span className="badge badge-unknown">Loading…</span>;
  }

  const enabled = status.enabled;
  const badgeClass = enabled ? 'badge-completed' : 'badge-idle';
  const label = enabled ? 'Active' : 'Inactive';

  return (
    <span className={`badge ${badgeClass}`} title={`Profile: ${status.defaultProfile || '—'}`}>
      {label}
    </span>
  );
}
