import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getRecentEvents, getSpeciesRanking, getStats, getStatus, getAiProviders } from '../api/observerApi';
import EventCard from '../components/observer/EventCard';
import SpeciesChart from '../components/observer/SpeciesChart';
import ObserverStatusBadge from '../components/observer/ObserverStatusBadge';

export default function ObserverDashboard() {
  const [events, setEvents] = useState([]);
  const [species, setSpecies] = useState([]);
  const [stats, setStats] = useState(null);
  const [status, setStatus] = useState(null);
  const [aiProviders, setAiProviders] = useState([]);
  const [error, setError] = useState(null);

  useEffect(() => {
    let cancelled = false;
    Promise.allSettled([
      getRecentEvents(5),
      getSpeciesRanking(),
      getStats(),
      getStatus(),
      getAiProviders(),
    ]).then(([evtResult, specResult, statsResult, statusResult, aiResult]) => {
      if (cancelled) return;
      if (evtResult.status === 'fulfilled') setEvents(evtResult.value || []);
      if (specResult.status === 'fulfilled') setSpecies(specResult.value || []);
      if (statsResult.status === 'fulfilled') setStats(statsResult.value);
      if (statusResult.status === 'fulfilled') setStatus(statusResult.value);
      if (aiResult.status === 'fulfilled') setAiProviders(aiResult.value || []);
      if (
        evtResult.status === 'rejected' &&
        specResult.status === 'rejected' &&
        statsResult.status === 'rejected'
      ) {
        setError('Unable to connect to observer backend.');
      }
    });
    return () => { cancelled = true; };
  }, []);

  return (
    <div className="page">
      <div className="page-header">
        <h1>Wildlife Observer</h1>
        <ObserverStatusBadge status={status} />
      </div>

      {error && <p className="error">{error}</p>}

      <section className="status-summary">
        <h2>Overview</h2>
        <div className="status-cards">
          <div className="card">
            <h3>Events</h3>
            <p className="card-value">{stats?.totalEvents ?? '—'}</p>
            <p className="card-label">total observations</p>
          </div>
          <div className="card">
            <h3>Species</h3>
            <p className="card-value">{stats?.totalSpecies ?? '—'}</p>
            <p className="card-label">identified</p>
          </div>
          <div className="card">
            <h3>Profile</h3>
            <p className="card-value">{status?.defaultProfile ?? '—'}</p>
            <p className="card-label">active profile</p>
          </div>
          <div className="card">
            <h3>AI Provider</h3>
            <p className="card-value">{status?.preferredAiProvider ?? '—'}</p>
            <p className="card-label">classification engine</p>
          </div>
        </div>
      </section>

      <section className="quick-actions">
        <h2>Quick Actions</h2>
        <div className="action-buttons">
          <Link to="/observer/events" className="btn btn-primary">
            View All Events
          </Link>
          <Link to="/observer/gallery" className="btn btn-secondary">
            Photo Gallery
          </Link>
          <Link to="/observer/statistics" className="btn btn-secondary">
            Statistics
          </Link>
          <Link to="/observer/settings" className="btn btn-secondary">
            Settings
          </Link>
        </div>
      </section>

      <section className="recent-events">
        <h2>Recent Observations</h2>
        {events.length === 0 ? (
          <p className="empty">No observations yet. Start the observer to begin monitoring.</p>
        ) : (
          <div className="card-grid">
            {events.map((evt) => (
              <Link key={evt.id} to={`/observer/events/${evt.id}`} style={{ textDecoration: 'none', color: 'inherit' }}>
                <EventCard event={evt} />
              </Link>
            ))}
          </div>
        )}
      </section>

      {species.length > 0 && (
        <section className="species-ranking">
          <h2>Species Ranking</h2>
          <SpeciesChart species={species.slice(0, 10)} />
        </section>
      )}

      {aiProviders.length > 0 && (
        <section style={{ marginTop: '2rem' }}>
          <h2>AI Providers</h2>
          <div className="status-cards">
            {aiProviders.map((p) => (
              <div key={p.name} className="card">
                <h3>{p.name}</h3>
                <p className="card-value">
                  <span className={`badge badge-${p.configured ? 'completed' : 'idle'}`}>
                    {p.configured ? 'Configured' : 'Not Configured'}
                  </span>
                </p>
                <p className="card-label">Model: {p.model}</p>
              </div>
            ))}
          </div>
        </section>
      )}
    </div>
  );
}
