import { useEffect, useRef, useState } from 'react';
import { getEventOriginal, getEventPreview } from '../../api/observerApi';
import DetectionOverlay from './DetectionOverlay';
import './ObservationEventImages.css';

function readDetectionBoxes(value) {
  try {
    const boxes = JSON.parse(value);
    if (!Array.isArray(boxes)) return [];
    return boxes.filter((box) => box && typeof box.label === 'string' &&
      [box.x, box.y, box.width, box.height, box.confidence].every(Number.isFinite) &&
      box.x >= 0 && box.y >= 0 && box.width > 0 && box.height > 0 &&
      box.x + box.width <= 1.000001 && box.y + box.height <= 1.000001 &&
      box.confidence >= 0 && box.confidence <= 1);
  } catch {
    return [];
  }
}

export default function ObservationEventImages({ event }) {
  const { id, bestImagePath, bestImageFileName, previewImagePath, previewSource, aiResult } = event;
  const hasOriginal = Boolean(bestImagePath);
  const hasPreview = Boolean(previewImagePath) ||
    (hasOriginal && /\.(jpe?g|png|gif|webp)$/i.test(bestImageFileName || bestImagePath));
  const liveView = previewSource === 'live-view-at-trigger';
  const boxes = readDetectionBoxes(aiResult?.boundingBoxesJson);

  const [previewUrl, setPreviewUrl] = useState(null);
  const [previewError, setPreviewError] = useState(null);
  const [downloading, setDownloading] = useState(false);
  const [downloadError, setDownloadError] = useState(null);
  const download = useRef(null);

  useEffect(() => {
    if (!hasPreview) return;
    const controller = new AbortController();
    let url;
    async function loadPreview() {
      try {
        const bytes = await getEventPreview(id, controller.signal);
        if (controller.signal.aborted) return;
        if (!bytes) throw new Error('Preview unavailable.');
        url = URL.createObjectURL(bytes);
        setPreviewUrl(url);
      } catch (err) {
        if (!controller.signal.aborted) setPreviewError(err.message);
      }
    }
    loadPreview();
    return () => {
      controller.abort();
      if (url) URL.revokeObjectURL(url);
    };
  }, [hasPreview, id]);

  useEffect(() => () => download.current?.abort(), []);

  async function downloadOriginal() {
    if (download.current) return;
    const controller = new AbortController();
    download.current = controller;
    setDownloading(true);
    setDownloadError(null);
    try {
      const bytes = await getEventOriginal(id, controller.signal);
      if (controller.signal.aborted) return;
      if (!bytes) throw new Error('Original unavailable.');
      const url = URL.createObjectURL(bytes);
      const link = document.createElement('a');
      link.href = url;
      link.download = bestImageFileName || 'observation-' + id;
      document.body.append(link);
      try {
        link.click();
      } finally {
        link.remove();
        setTimeout(() => URL.revokeObjectURL(url), 150);
      }
    } catch (err) {
      if (!controller.signal.aborted) setDownloadError(err.message);
    } finally {
      download.current = null;
      if (!controller.signal.aborted) setDownloading(false);
    }
  }

  if (!hasOriginal && !hasPreview) return null;

  return (
    <section className="detail-section observation-images" aria-labelledby="observation-images-title">
      <h2 id="observation-images-title">Observation images</h2>
      {previewError && <p className="error" role="alert">Could not show preview. {previewError}</p>}
      {downloadError && <p className="error" role="alert">Could not download original. {downloadError}</p>}
      {hasPreview && !previewError && (
        previewUrl ? (
          <figure className="observation-image">
            <div className="observation-image-frame">
              <img
                src={previewUrl}
                alt="Observation preview"
                onError={() => setPreviewError('The image could not be decoded.')}
              />
              {liveView && boxes.length > 0 && <DetectionOverlay boxes={boxes} />}
            </div>
            {previewSource && <figcaption>{liveView ? 'Live view at trigger' : 'Captured photo'}</figcaption>}
          </figure>
        ) : <p role="status">Loading observation preview…</p>
      )}
      {!liveView && boxes.length > 0 && (
        <div className="observation-trigger-detections">
          <strong>Trigger detections</strong>
          <ul>
            {boxes.map((box, index) => <li key={index}>{box.label} {Math.round(box.confidence * 100)}%</li>)}
          </ul>
        </div>
      )}
      {hasOriginal && (
        <button className="btn btn-primary" type="button" onClick={downloadOriginal} disabled={downloading}>
          {downloading ? 'Downloading…' : 'Download original'}
        </button>
      )}
    </section>
  );
}
