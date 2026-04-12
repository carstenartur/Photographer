import { useEffect, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { getEvent } from '../api/observerApi';
import AiResultPanel from '../components/observer/AiResultPanel';
import EventTimeline from '../components/observer/EventTimeline';

export default function ObserverEventDetail() {
  const { id } = useParams();
  const [event, setEvent] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    getEvent(id)
      .then((data) => { if (!cancelled) setEvent(data); })
      .catch((err) => { if (!cancelled) setError(`Failed to load event: ${err.message}`); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [id]);

  if (loading) return <div className="page"><p>Loading…</p></div>;
  if (error) return <div className="page"><p className="error">{error}</p></div>;
  if (!event) return <div className="page"><p className="empty">Event not found.</p></div>;

  return (
    <div className="page">
      <div className="page-header">
        <h1>Event Detail</h1>
        <Link to="/observer/events" className="btn btn-secondary">← Back to Events</Link>
      </div>

      <EventTimeline currentState={event.state} />

      <div className="form-card" style={{ marginTop: '1.5rem' }}>
        <h2>Event Information</h2>
        <div className="detail-grid">
          <div className="detail-item">
            <span className="detail-label">ID</span>
            <span className="detail-value">{event.id}</span>
          </div>
          <div className="detail-item">
            <span className="detail-label">State</span>
            <span className="detail-value">
              <span className={`badge badge-${(event.state || '').toLowerCase()}`}>
                {event.state}
              </span>
            </span>
          </div>
          <div className="detail-item">
            <span className="detail-label">Mode</span>
            <span className="detail-value">{event.mode || '—'}</span>
          </div>
          <div className="detail-item">
            <span className="detail-label">Trigger</span>
            <span className="detail-value">{event.triggerReason || '—'}</span>
          </div>
          <div className="detail-item">
            <span className="detail-label">Camera</span>
            <span className="detail-value">{event.cameraId || '—'}</span>
          </div>
          <div className="detail-item">
            <span className="detail-label">Detected</span>
            <span className="detail-value">
              {event.timestamp ? new Date(event.timestamp).toLocaleString() : '—'}
            </span>
          </div>
          <div className="detail-item">
            <span className="detail-label">Completed</span>
            <span className="detail-value">
              {event.completedAt ? new Date(event.completedAt).toLocaleString() : '—'}
            </span>
          </div>
          {event.bestImageFileName && (
            <div className="detail-item">
              <span className="detail-label">Best Image</span>
              <span className="detail-value">{event.bestImageFileName}</span>
            </div>
          )}
        </div>
      </div>

      <AiResultPanel aiResult={event.aiResult} />
    </div>
  );
}
