import { useEffect, useState } from 'react';
import { getSpeciesRanking, getStats, getEventsByDate, getProfiles } from '../api/observerApi';
import SpeciesChart from '../components/observer/SpeciesChart';

export default function ObserverStatistics() {
  const [species, setSpecies] = useState([]);
  const [stats, setStats] = useState(null);
  const [todayEvents, setTodayEvents] = useState([]);
  const [profiles, setProfiles] = useState([]);
  const [error, setError] = useState(null);

  useEffect(() => {
    Promise.allSettled([
      getSpeciesRanking(),
      getStats(),
      getEventsByDate(), // defaults to today
      getProfiles(),
    ]).then(([specRes, statsRes, todayRes, profilesRes]) => {
      if (specRes.status === 'fulfilled') setSpecies(specRes.value || []);
      if (statsRes.status === 'fulfilled') setStats(statsRes.value);
      if (todayRes.status === 'fulfilled') setTodayEvents(todayRes.value || []);
      if (profilesRes.status === 'fulfilled') setProfiles(profilesRes.value || []);
      if (specRes.status === 'rejected' && statsRes.status === 'rejected') {
        setError('Unable to load statistics.');
      }
    });
  }, []);

  // Compute hourly distribution for today's events
  const hourlyDistribution = new Array(24).fill(0);
  todayEvents.forEach((evt) => {
    if (evt.timestamp) {
      const hour = new Date(evt.timestamp).getHours();
      hourlyDistribution[hour]++;
    }
  });
  const maxHourly = Math.max(...hourlyDistribution, 1);

  return (
    <div className="page">
      <h1>Observer Statistics</h1>

      {error && <p className="error">{error}</p>}

      <section className="status-summary">
        <h2>Summary</h2>
        <div className="status-cards">
          <div className="card">
            <h3>Total Events</h3>
            <p className="card-value">{stats?.totalEvents ?? '—'}</p>
          </div>
          <div className="card">
            <h3>Species Identified</h3>
            <p className="card-value">{stats?.totalSpecies ?? '—'}</p>
          </div>
          <div className="card">
            <h3>Today</h3>
            <p className="card-value">{todayEvents.length}</p>
            <p className="card-label">events</p>
          </div>
        </div>
      </section>

      <section style={{ marginTop: '2rem' }}>
        <h2>Species Ranking</h2>
        <SpeciesChart species={species} />
      </section>

      <section style={{ marginTop: '2rem' }}>
        <h2>Today&apos;s Activity by Hour</h2>
        {todayEvents.length === 0 ? (
          <p className="empty">No events today.</p>
        ) : (
          <div className="hourly-chart">
            {hourlyDistribution.map((count, hour) => (
              <div key={hour} className="hourly-bar-col">
                <div
                  className="hourly-bar"
                  style={{ height: `${(count / maxHourly) * 100}%` }}
                  title={`${hour}:00 — ${count} events`}
                />
                <span className="hourly-label">{hour}</span>
              </div>
            ))}
          </div>
        )}
      </section>

      {profiles.length > 0 && (
        <section style={{ marginTop: '2rem' }}>
          <h2>Available Profiles</h2>
          <table>
            <thead>
              <tr>
                <th>Profile</th>
                <th>Mode</th>
                <th>Burst</th>
                <th>Cooldown</th>
                <th>Max/Hour</th>
                <th>Active Window</th>
              </tr>
            </thead>
            <tbody>
              {profiles.map((p) => (
                <tr key={p.name}>
                  <td><strong>{p.name}</strong></td>
                  <td>{p.mode}</td>
                  <td>{p.burstCount}</td>
                  <td>{p.cooldownSeconds}s</td>
                  <td>{p.maxEventsPerHour}</td>
                  <td>{p.activeTimeStart} – {p.activeTimeEnd}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}
    </div>
  );
}
