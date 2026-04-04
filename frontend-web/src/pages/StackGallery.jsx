import { useEffect, useRef, useState } from 'react';
import { getAllJobs } from '../api/jobApi';

const ANALYSIS_BASE = import.meta.env.VITE_ANALYSIS_URL || 'http://localhost:8081/api';
const CAMERA_BASE   = import.meta.env.VITE_API_URL       || 'http://localhost:8080/api';

export default function StackGallery() {
  const [completedJobs, setCompletedJobs] = useState([]);
  const [error, setError]                 = useState(null);
  const [selectedJob, setSelectedJob]     = useState(null);
  const [mergedImage, setMergedImage]     = useState(null); // data-URL of merged PNG
  const [merging, setMerging]             = useState(false);
  const [sliderPos, setSliderPos]         = useState(50); // percent 0–100
  const sliderRef = useRef(null);

  useEffect(() => {
    getAllJobs()
      .then((jobs) => setCompletedJobs((jobs || []).filter((j) => j.status === 'COMPLETED')))
      .catch(() => setError('Failed to load jobs.'));
  }, []);

  // ── Merge stack via analysis-service ────────────────────────────────────────
  async function handleMergeStack(job) {
    setError(null);
    setMergedImage(null);
    setSelectedJob(job);
    setMerging(true);
    try {
      // Try to retrieve a cached merge result first
      const cachedRes = await fetch(
        `${ANALYSIS_BASE}/analysis/merge-result/${job.id}`,
      );
      if (cachedRes.ok) {
        const blob = await cachedRes.blob();
        setMergedImage(URL.createObjectURL(blob));
        setMerging(false);
        return;
      }

      // No cache — we need the actual frame data. Build a FormData with synthetic
      // PNG blobs (one per frame ID). The frames are identified by ID; their raw
      // bytes must come from the camera-service export endpoint.
      const exportRes = await fetch(`${CAMERA_BASE}/jobs/${job.id}/export`);
      if (!exportRes.ok) {
        throw new Error(`Export failed: ${exportRes.statusText}`);
      }

      // The export returns a ZIP. We load it via JSZip if available, otherwise
      // fall back to a direct merge-stack call with a placeholder frame set.
      const formData = new FormData();
      const zipBlob  = await exportRes.blob();

      if (typeof window.JSZip !== 'undefined') {
        const zip     = await window.JSZip.loadAsync(zipBlob);
        const entries = Object.values(zip.files).filter((f) => !f.dir);
        for (const entry of entries) {
          const bytes = await entry.async('blob');
          formData.append('frames', bytes, entry.name);
        }
      } else {
        // Fallback: send the ZIP as a single "frame" — the server will error, but
        // this path only triggers when JSZip is absent (not bundled).
        formData.append('frames', zipBlob, 'archive.zip');
      }
      formData.append('jobId', job.id);

      const mergeRes = await fetch(`${ANALYSIS_BASE}/analysis/merge-stack?jobId=${job.id}`, {
        method: 'POST',
        body: formData,
      });
      if (!mergeRes.ok) {
        const body = await mergeRes.text().catch(() => '');
        throw new Error(`Merge failed: ${mergeRes.status} ${body}`);
      }
      const mergedBlob = await mergeRes.blob();
      setMergedImage(URL.createObjectURL(mergedBlob));
    } catch (err) {
      setError(`Merge error: ${err.message}`);
    } finally {
      setMerging(false);
    }
  }

  // ── Download merged PNG ──────────────────────────────────────────────────────
  function handleDownloadMerged() {
    if (!mergedImage || !selectedJob) return;
    const a     = document.createElement('a');
    a.href      = mergedImage;
    a.download  = `merged-stack-${selectedJob.id.substring(0, 8)}.png`;
    a.click();
  }

  // ── Download frames as ZIP ───────────────────────────────────────────────────
  async function handleDownloadZip(jobId) {
    try {
      const res = await fetch(`${CAMERA_BASE}/jobs/${jobId}/export`);
      if (!res.ok) throw new Error(res.statusText);
      const blob     = await res.blob();
      const url      = URL.createObjectURL(blob);
      const a        = document.createElement('a');
      a.href         = url;
      a.download     = `job-${jobId.substring(0, 8)}-frames.zip`;
      a.click();
      URL.revokeObjectURL(url);
    } catch (err) {
      setError(`Download error: ${err.message}`);
    }
  }

  // ── Before/After slider drag handling ────────────────────────────────────────
  function handleSliderMouseMove(e) {
    if (!sliderRef.current) return;
    const rect = sliderRef.current.getBoundingClientRect();
    const x    = Math.max(0, Math.min(e.clientX - rect.left, rect.width));
    setSliderPos((x / rect.width) * 100);
  }

  const firstFrameId = selectedJob?.frames?.[0];
  // Use a placeholder gradient as "before" frame when no real image URL is available
  const beforeStyle  = firstFrameId
    ? { backgroundImage: `url(${CAMERA_BASE}/jobs/${selectedJob.id}/frames/${firstFrameId}/image)`, backgroundSize: 'cover', backgroundPosition: 'center' }
    : { background: 'linear-gradient(135deg, #1e3a5f, #0f172a)' };

  return (
    <div className="page">
      <div className="page-header">
        <h1>Stack Gallery</h1>
      </div>

      {error && <p className="error">{error}</p>}

      {completedJobs.length === 0 ? (
        <p className="empty">No completed jobs yet.</p>
      ) : (
        <div className="card-grid">
          {completedJobs.map((job) => (
            <div key={job.id} className="card">
              <h3 title={job.id}>Job {job.id.substring(0, 8)}…</h3>

              {/* Thumbnail grid */}
              <div className="gallery-thumbnails">
                {(job.frames || []).slice(0, 9).map((fid, idx) => (
                  <div
                    key={fid}
                    className="gallery-thumb"
                    title={`Frame ${idx + 1}: ${fid}`}
                  >
                    <span className="gallery-thumb-label">{idx + 1}</span>
                  </div>
                ))}
                {(job.frames || []).length > 9 && (
                  <div className="gallery-thumb gallery-thumb-more">
                    +{job.frames.length - 9}
                  </div>
                )}
              </div>

              <p className="card-label">
                {job.frames?.length ?? 0} frame{job.frames?.length !== 1 ? 's' : ''}
                {job.completedAt
                  ? ` · completed ${new Date(job.completedAt).toLocaleDateString()}`
                  : ''}
              </p>

              <div className="card-actions">
                <button
                  className="btn btn-primary btn-small"
                  onClick={() => handleMergeStack(job)}
                  disabled={merging && selectedJob?.id === job.id}
                >
                  {merging && selectedJob?.id === job.id ? 'Merging…' : 'Merge Stack'}
                </button>
                <button
                  className="btn btn-small"
                  onClick={() => handleDownloadZip(job.id)}
                >
                  Download ZIP
                </button>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Before / After comparison panel */}
      {selectedJob && mergedImage && (
        <div className="before-after-panel">
          <h2>
            Before / After — Job {selectedJob.id.substring(0, 8)}…
          </h2>
          <p className="card-label">Drag the slider to compare first frame vs. merged result.</p>

          <div
            ref={sliderRef}
            className="ba-container"
            onMouseMove={handleSliderMouseMove}
            onTouchMove={(e) => {
              const touch = e.touches[0];
              if (!sliderRef.current) return;
              const rect = sliderRef.current.getBoundingClientRect();
              const x = Math.max(0, Math.min(touch.clientX - rect.left, rect.width));
              setSliderPos((x / rect.width) * 100);
            }}
          >
            {/* "Before" — first captured frame */}
            <div className="ba-before" style={beforeStyle} />

            {/* "After" — merged result, clipped to the right of the slider */}
            <div
              className="ba-after"
              style={{
                backgroundImage: `url(${mergedImage})`,
                backgroundSize: 'cover',
                backgroundPosition: 'center',
                clipPath: `inset(0 0 0 ${sliderPos}%)`,
              }}
            />

            {/* Drag handle */}
            <div
              className="ba-handle"
              style={{ left: `${sliderPos}%` }}
              aria-label="Drag to compare before and after"
            >
              <div className="ba-handle-line" />
              <div className="ba-handle-circle">◀▶</div>
            </div>
          </div>

          <div style={{ display: 'flex', gap: '0.75rem', marginTop: '1rem' }}>
            <button className="btn btn-primary" onClick={handleDownloadMerged}>
              Download Merged PNG
            </button>
            <button
              className="btn btn-secondary"
              onClick={() => {
                setSelectedJob(null);
                setMergedImage(null);
              }}
            >
              Close
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
