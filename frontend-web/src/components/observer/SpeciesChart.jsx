/**
 * Horizontal bar chart for species ranking.
 */
export default function SpeciesChart({ species }) {
  if (!species || species.length === 0) {
    return <p className="empty">No species data yet.</p>;
  }

  const maxSightings = Math.max(...species.map((s) => s.totalSightings || 0), 1);

  return (
    <div className="species-chart">
      {species.map((s) => {
        const pct = Math.round(((s.totalSightings || 0) / maxSightings) * 100);
        return (
          <div key={s.normalizedNameDe || s.id} className="species-bar-row">
            <span className="species-bar-label">
              {s.normalizedNameDe || 'Unknown'}
            </span>
            <div className="species-bar-track">
              <div
                className="species-bar-fill"
                style={{ width: `${pct}%` }}
              />
            </div>
            <span className="species-bar-count">{s.totalSightings || 0}</span>
          </div>
        );
      })}
    </div>
  );
}
