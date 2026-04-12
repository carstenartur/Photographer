import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getRecentEvents } from '../api/observerApi';

export default function ObserverGallery() {
  const [events, setEvents] = useState([]);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState('all');

  useEffect(() => {
    let cancelled = false;
    getRecentEvents(100)
      .then((data) => { if (!cancelled) setEvents((data || []).filter((e) => e.state === 'COMPLETED')); })
      .catch((err) => { if (!cancelled) setError(`Failed to load gallery: ${err.message}`); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, []);

  const filteredEvents =
    filter === 'all'
      ? events
      : events.filter((e) => e.mode === filter);

  // Group events by species label
  const speciesGroups = {};
  filteredEvents.forEach((evt) => {
    const label = evt.aiResult?.primaryLabel || 'Unknown';
    if (!speciesGroups[label]) speciesGroups[label] = [];
    speciesGroups[label].push(evt);
  });

  return (
    <div className="page">
      <div className="page-header">
        <h1>Observer Gallery</h1>
        <div className="filter-bar">
          <label>
            Filter:
            <select value={filter} onChange={(e) => setFilter(e.target.value)}>
              <option value="all">All</option>
              <option value="wildlife">Wildlife</option>
              <option value="macro_pollinator">Macro / Pollinator</option>
            </select>
          </label>
        </div>
      </div>

      {error && <p className="error">{error}</p>}

      {loading ? (
        <p>Loading gallery…</p>
      ) : filteredEvents.length === 0 ? (
        <p className="empty">No completed observations for the gallery yet.</p>
      ) : (
        Object.entries(speciesGroups)
          .sort(([, a], [, b]) => b.length - a.length)
          .map(([label, groupEvents]) => (
            <section key={label} style={{ marginBottom: '2rem' }}>
              <h2>
                {label}{' '}
                <span className="card-label">
                  ({groupEvents.length} sighting{groupEvents.length !== 1 ? 's' : ''})
                </span>
              </h2>
              <div className="card-grid">
                {groupEvents.map((evt) => (
                  <Link
                    key={evt.id}
                    to={`/observer/events/${evt.id}`}
                    style={{ textDecoration: 'none', color: 'inherit' }}
                  >
                    <div className="card gallery-card">
                      <div className="gallery-card-placeholder">
                        <span>{evt.aiResult?.primaryLabel?.charAt(0) || '?'}</span>
                      </div>
                      <p className="gallery-card-info">
                        {evt.aiResult?.confidence != null
                          ? `${Math.round(evt.aiResult.confidence * 100)}%`
                          : '—'}{' '}
                        · {evt.timestamp ? new Date(evt.timestamp).toLocaleDateString() : '—'}
                      </p>
                      {evt.aiResult?.summary && (
                        <p className="gallery-card-summary">{evt.aiResult.summary}</p>
                      )}
                    </div>
                  </Link>
                ))}
              </div>
            </section>
          ))
      )}
    </div>
  );
}
