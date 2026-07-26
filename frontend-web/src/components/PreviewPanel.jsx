import { useState, useEffect, useRef } from 'react';
import { apiUrl, webSocketUrl } from '../api/auth';

/**
 * Live preview panel that shows the latest camera frame and/or the
 * progressive focus-stacked result during a running job.
 *
 * Receives preview data via the parent's WebSocket messages.
 */
export default function PreviewPanel({ jobId, isRunning, frames = [] }) {
  const [showCamera, setShowCamera] = useState(true);
  const [showStack, setShowStack] = useState(true);
  const [cameraFrame, setCameraFrame] = useState(null);
  const [stackFrame, setStackFrame] = useState(null);
  const [cameraIndex, setCameraIndex] = useState(-1);
  const [stackInfo, setStackInfo] = useState(null);
  const wsRef = useRef(null);

  useEffect(() => {
    if (!isRunning || !jobId) return;

    let ws;
    try {
      ws = new WebSocket(webSocketUrl());
      wsRef.current = ws;

      ws.onmessage = (event) => {
        try {
          const msg = JSON.parse(event.data);
          if (msg.type === 'camera' && msg.imageBase64) {
            setCameraFrame(msg.imageBase64);
            setCameraIndex(msg.frameIndex);
          } else if (msg.type === 'stack' && msg.imageBase64) {
            setStackFrame(msg.imageBase64);
            setStackInfo({ framesUsed: msg.framesUsed, frameIndex: msg.frameIndex });
          }
        } catch { /* ignore non-JSON */ }
      };
      ws.onerror = () => {};
    } catch { /* WebSocket not available */ }

    return () => {
      if (ws) ws.close();
      wsRef.current = null;
    };
  }, [jobId, isRunning]);

  if (!isRunning && !cameraFrame && !stackFrame) {
    // For completed/non-running jobs with captured frames, show the last frame as a static preview
    if (frames.length > 0) {
      const lastFrameId = frames[frames.length - 1];
      const firstFrameId = frames[0];
      return (
        <section className="detail-section">
          <h2>Preview</h2>
          <div className="preview-grid">
            <div className="preview-pane">
              <h3>First Frame</h3>
              <img
                src={apiUrl(`/jobs/${jobId}/frames/${firstFrameId}/image`)}
                alt="First captured frame"
                className="preview-image"
                onError={(e) => { e.target.style.display = 'none'; }}
              />
            </div>
            {frames.length > 1 && (
              <div className="preview-pane">
                <h3>Last Frame <span className="preview-meta">— #{frames.length}</span></h3>
                <img
                  src={apiUrl(`/jobs/${jobId}/frames/${lastFrameId}/image`)}
                  alt="Last captured frame"
                  className="preview-image"
                  onError={(e) => { e.target.style.display = 'none'; }}
                />
              </div>
            )}
          </div>
        </section>
      );
    }
    return null; // Don't render panel when no data and not running
  }

  return (
    <section className="detail-section">
      <h2>Live Preview</h2>

      <div className="preview-toggles">
        <label className="preview-toggle">
          <input
            type="checkbox"
            checked={showCamera}
            onChange={(e) => setShowCamera(e.target.checked)}
          />
          Camera View
        </label>
        <label className="preview-toggle">
          <input
            type="checkbox"
            checked={showStack}
            onChange={(e) => setShowStack(e.target.checked)}
          />
          Focus Stack Preview
        </label>
      </div>

      <div className="preview-grid">
        {showCamera && (
          <div className="preview-pane">
            <h3>
              Camera Frame
              {cameraIndex >= 0 && (
                <span className="preview-meta"> — #{cameraIndex + 1}</span>
              )}
            </h3>
            {cameraFrame ? (
              <img
                src={`data:image/png;base64,${cameraFrame}`}
                alt="Latest camera frame"
                className="preview-image"
              />
            ) : (
              <div className="preview-placeholder">
                {isRunning ? 'Waiting for first frame…' : 'No preview available'}
              </div>
            )}
          </div>
        )}

        {showStack && (
          <div className="preview-pane">
            <h3>
              Stacked Result
              {stackInfo && (
                <span className="preview-meta">
                  {' '}— {stackInfo.framesUsed} frames merged
                </span>
              )}
            </h3>
            {stackFrame ? (
              <img
                src={`data:image/png;base64,${stackFrame}`}
                alt="Progressive stack merge"
                className="preview-image"
              />
            ) : (
              <div className="preview-placeholder">
                {isRunning
                  ? 'Stack preview appears after 2 frames…'
                  : 'No stack preview available'}
              </div>
            )}
          </div>
        )}
      </div>
    </section>
  );
}
