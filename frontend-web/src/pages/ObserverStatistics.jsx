import { useEffect, useState } from 'react';
import { getSpeciesRanking, getStats, getEventsByDate, getProfiles, getDailyStats, getSpeciesDetail } from '../api/observerApi';
import SpeciesChart from '../components/observer/SpeciesChart';

export default function ObserverStatistics() {
  const [species, setSpecies] = useState([]);
  const [stats, setStats] = useState(null);
  const [todayEvents, setTodayEvents] = useState([]);
  const [profiles, setProfiles] = useState([]);
  const [dailyStats, setDailyStats] = useState([]);
  const [selectedSpecies, setSelectedSpecies] = useState(null);
  const [speciesDetail, setSpeciesDetail] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    let cancelled = false;
    Promise.allSettled([
      getSpeciesRanking(),
      getStats(),
      getEventsByDate(), // defaults to today
      getProfiles(),
      getDailyStats(14),
    ]).then(([specRes, statsRes, todayRes, profilesRes, dailyRes]) => {
      if (cancelled) return;
      if (specRes.status === 'fulfilled') setSpecies(specRes.value || []);
      if (statsRes.status === 'fulfilled') setStats(statsRes.value);
      if (todayRes.status === 'fulfilled') setTodayEvents(todayRes.value || []);
      if (profilesRes.status === 'fulfilled') setProfiles(profilesRes.value || []);
      if (dailyRes.status === 'fulfilled') setDailyStats(dailyRes.value || []);
      if (specRes.status === 'rejected' && statsRes.status === 'rejected') {
        setError('Unable to load statistics.');
      }
    });
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    if (!selectedSpecies) {
      // No species selected — clear detail asynchronously via microtask
      let cancelled = false;
      Promise.resolve().then(() => { if (!cancelled) setSpeciesDetail(null); });
      return () => { cancelled = true; };
    }
    let cancelled = false;
    getSpeciesDetail(selectedSpecies)
      .then((data) => { if (!cancelled) setSpeciesDetail(data); })
      .catch(() => { if (!cancelled) setSpeciesDetail(null); });
    return () => { cancelled = true; };
  }, [selectedSpecies]);

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
        {species.length > 0 && (
          <div style={{ marginTop: '1rem' }}>
            <label htmlFor="species-select"><strong>View species detail: </strong></label>
            <select
              id="species-select"
              value={selectedSpecies || ''}
              onChange={(e) => setSelectedSpecies(e.target.value || null)}
            >
              <option value="">— select —</option>
              {species.map((s) => (
                <option key={s.normalizedNameDe} value={s.normalizedNameDe}>
                  {s.normalizedNameDe} ({s.totalSightings})
                </option>
              ))}
            </select>
          </div>
        )}
        {speciesDetail && (
          <div className="form-card" style={{ marginTop: '1rem' }}>
            <h3>{speciesDetail.normalizedNameDe}</h3>
            <div className="detail-grid">
              <div className="detail-item">
                <span className="detail-label">Category</span>
                <span className="detail-value">{speciesDetail.category || '—'}</span>
              </div>
              <div className="detail-item">
                <span className="detail-label">Total Sightings</span>
                <span className="detail-value">{speciesDetail.totalSightings}</span>
              </div>
              <div className="detail-item">
                <span className="detail-label">First Seen</span>
                <span className="detail-value">
                  {speciesDetail.firstSeen ? new Date(speciesDetail.firstSeen).toLocaleString() : '—'}
                </span>
              </div>
              <div className="detail-item">
                <span className="detail-label">Last Seen</span>
                <span className="detail-value">
                  {speciesDetail.lastSeen ? new Date(speciesDetail.lastSeen).toLocaleString() : '—'}
                </span>
              </div>
              {speciesDetail.scientificName && (
                <div className="detail-item">
                  <span className="detail-label">Scientific Name</span>
                  <span className="detail-value">{speciesDetail.scientificName}</span>
                </div>
              )}
            </div>
          </div>
        )}
      </section>

      {dailyStats.length > 0 && (
        <section style={{ marginTop: '2rem' }}>
          <h2>Daily Activity (Last 14 Days)</h2>
          <div className="hourly-chart">
            {dailyStats.map((d) => {
              const maxDaily = Math.max(...dailyStats.map((x) => x.count), 1);
              return (
                <div key={d.date} className="hourly-bar-col">
                  <div
                    className="hourly-bar"
                    style={{ height: `${(d.count / maxDaily) * 100}%` }}
                    title={`${d.date}: ${d.count} events`}
                  />
                  <span className="hourly-label" style={{ fontSize: '0.65rem' }}>
                    {d.date.slice(5)}
                  </span>
                </div>
              );
            })}
          </div>
        </section>
      )}

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
