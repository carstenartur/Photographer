/**
 * Displays a compact observation event card.
 */
export default function EventCard({ event, onClick }) {
  const aiResult = event.aiResult;
  const label = aiResult?.primaryLabel || 'Unknown';
  const confidence = label === 'unclassified' ? null : aiResult?.confidence;
  const time = event.timestamp
    ? new Date(event.timestamp).toLocaleTimeString()
    : '—';

  return (
    <div className="card event-card">
      <div className="event-card-header">
        <span className={`badge badge-${(event.state || '').toLowerCase()}`}>
          {event.state || 'UNKNOWN'}
        </span>
        <span className="event-card-time">{time}</span>
      </div>
      <h3 className="event-card-label">
        {onClick ? (
          <button type="button" className="event-card-open" onClick={() => onClick(event)}>
            {label}
          </button>
        ) : label}
      </h3>
      {confidence != null && (
        <div className="event-card-confidence">
          <div className="confidence-bar" style={{ width: `${Math.round(confidence * 100)}%` }} />
          <span>{Math.round(confidence * 100)}%</span>
        </div>
      )}
      <p className="card-label">
        {event.mode || '—'} · {event.triggerReason || '—'}
      </p>
      {aiResult?.summary && (
        <p className="event-card-summary">{aiResult.summary}</p>
      )}
    </div>
  );
}
