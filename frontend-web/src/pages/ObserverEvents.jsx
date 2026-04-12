import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getRecentEvents, getEventsByMode } from '../api/observerApi';

export default function ObserverEvents() {
  const [events, setEvents] = useState([]);
  const [filter, setFilter] = useState('all');
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;

    const fetchEvents =
      filter === 'all'
        ? getRecentEvents(100)
        : getEventsByMode(filter, 100);

    fetchEvents
      .then((data) => { if (!cancelled) setEvents(data || []); })
      .catch((err) => { if (!cancelled) setError(`Failed to load events: ${err.message}`); })
      .finally(() => { if (!cancelled) setLoading(false); });

    return () => { cancelled = true; };
  }, [filter]);

  return (
    <div className="page">
      <div className="page-header">
        <h1>Observation Events</h1>
        <div className="filter-bar">
          <label>
            Filter:
            <select value={filter} onChange={(e) => setFilter(e.target.value)}>
              <option value="all">All Events</option>
              <option value="wildlife">Wildlife</option>
              <option value="macro_pollinator">Macro / Pollinator</option>
            </select>
          </label>
        </div>
      </div>

      {error && <p className="error">{error}</p>}

      {loading ? (
        <p>Loading events…</p>
      ) : events.length === 0 ? (
        <p className="empty">No events found.</p>
      ) : (
        <>
          <p className="card-label">{events.length} event{events.length !== 1 ? 's' : ''}</p>
          <table>
            <thead>
              <tr>
                <th>Time</th>
                <th>Species</th>
                <th>Confidence</th>
                <th>Mode</th>
                <th>Trigger</th>
                <th>State</th>
              </tr>
            </thead>
            <tbody>
              {events.map((evt) => (
                <tr key={evt.id}>
                  <td>
                    <Link to={`/observer/events/${evt.id}`}>
                      {evt.timestamp ? new Date(evt.timestamp).toLocaleString() : '—'}
                    </Link>
                  </td>
                  <td>{evt.aiResult?.primaryLabel || '—'}</td>
                  <td>
                    {evt.aiResult?.confidence != null
                      ? `${Math.round(evt.aiResult.confidence * 100)}%`
                      : '—'}
                  </td>
                  <td>{evt.mode || '—'}</td>
                  <td>{evt.triggerReason || '—'}</td>
                  <td>
                    <span className={`badge badge-${(evt.state || '').toLowerCase()}`}>
                      {evt.state || 'UNKNOWN'}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </div>
  );
}
