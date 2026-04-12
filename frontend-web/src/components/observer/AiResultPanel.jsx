/**
 * Panel showing AI classification results for an observation event.
 */
export default function AiResultPanel({ aiResult }) {
  if (!aiResult) {
    return (
      <div className="ai-result-panel">
        <p className="empty">No AI analysis available.</p>
      </div>
    );
  }

  const alternatives = aiResult.alternativeLabelsJson
    ? aiResult.alternativeLabelsJson.split(',').filter(Boolean)
    : [];

  return (
    <div className="ai-result-panel">
      <h3>AI Classification</h3>
      <div className="ai-result-grid">
        <div className="ai-result-item">
          <span className="ai-result-label">Species</span>
          <span className="ai-result-value ai-result-primary">{aiResult.primaryLabel || '—'}</span>
        </div>
        <div className="ai-result-item">
          <span className="ai-result-label">Confidence</span>
          <span className="ai-result-value">
            {aiResult.confidence != null ? `${Math.round(aiResult.confidence * 100)}%` : '—'}
          </span>
        </div>
        <div className="ai-result-item">
          <span className="ai-result-label">Provider</span>
          <span className="ai-result-value">{aiResult.provider || '—'}</span>
        </div>
        <div className="ai-result-item">
          <span className="ai-result-label">Model</span>
          <span className="ai-result-value">{aiResult.model || '—'}</span>
        </div>
        <div className="ai-result-item">
          <span className="ai-result-label">Duration</span>
          <span className="ai-result-value">
            {aiResult.durationMs != null ? `${aiResult.durationMs}ms` : '—'}
          </span>
        </div>
        {alternatives.length > 0 && (
          <div className="ai-result-item ai-result-wide">
            <span className="ai-result-label">Alternatives</span>
            <span className="ai-result-value">{alternatives.join(', ')}</span>
          </div>
        )}
        {aiResult.summary && (
          <div className="ai-result-item ai-result-wide">
            <span className="ai-result-label">Summary</span>
            <span className="ai-result-value">{aiResult.summary}</span>
          </div>
        )}
      </div>
    </div>
  );
}
