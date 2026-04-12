import { useEffect, useState } from 'react';
import { useParams, Link, useNavigate } from 'react-router-dom';
import { getEvent, deleteEvent } from '../api/observerApi';
import AiResultPanel from '../components/observer/AiResultPanel';
import EventTimeline from '../components/observer/EventTimeline';

export default function ObserverEventDetail() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [event, setEvent] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);
  const [deleting, setDeleting] = useState(false);

  useEffect(() => {
    let cancelled = false;
    getEvent(id)
      .then((data) => { if (!cancelled) setEvent(data); })
      .catch((err) => { if (!cancelled) setError(`Failed to load event: ${err.message}`); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [id]);

  function handleDelete() {
    if (!window.confirm('Delete this observation event? This cannot be undone.')) return;
    setDeleting(true);
    deleteEvent(id)
      .then(() => navigate('/observer/events'))
      .catch((err) => {
        setError(`Failed to delete: ${err.message}`);
        setDeleting(false);
      });
  }

  if (loading) return <div className="page"><p>Loading…</p></div>;
  if (error) return <div className="page"><p className="error">{error}</p></div>;
  if (!event) return <div className="page"><p className="empty">Event not found.</p></div>;

  return (
    <div className="page">
      <div className="page-header">
        <h1>Event Detail</h1>
        <div style={{ display: 'flex', gap: '0.5rem' }}>
          <Link to="/observer/events" className="btn btn-secondary">← Back to Events</Link>
          <button
            className="btn btn-danger"
            onClick={handleDelete}
            disabled={deleting}
          >
            {deleting ? 'Deleting…' : 'Delete Event'}
          </button>
        </div>
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
