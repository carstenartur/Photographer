import { useEffect, useRef, useState } from 'react';
import { getAllJobs } from '../api/jobApi';
import { apiUrl, authenticatedFetch, requestBlob } from '../api/auth';

const ANALYSIS_BASE = (import.meta.env.VITE_ANALYSIS_URL || '').replace(/\/$/, '');

export default function StackGallery() {
  const [completedJobs, setCompletedJobs] = useState([]);
  const [error, setError]                 = useState(null);
  const [selectedJob, setSelectedJob]     = useState(null);
  const [mergedImage, setMergedImage]     = useState(null); // object URL of merged PNG
  const [mergeDetails, setMergeDetails]   = useState(null);
  const [merging, setMerging]             = useState(false);
  const [zipLibLoading, setZipLibLoading] = useState(false);
  const [sliderPos, setSliderPos]         = useState(50); // percent 0–100
  const sliderRef = useRef(null);
  // Track the current object URL so we can revoke it when replacing or unmounting
  const mergedImageUrlRef = useRef(null);

  useEffect(() => {
    getAllJobs()
      .then((jobs) => setCompletedJobs((jobs || []).filter((j) => j.status === 'COMPLETED')))
      .catch(() => setError('Failed to load jobs.'));
  }, []);

  // Revoke the previous merged image object URL to avoid memory leaks
  useEffect(() => {
    return () => {
      if (mergedImageUrlRef.current) {
        URL.revokeObjectURL(mergedImageUrlRef.current);
        mergedImageUrlRef.current = null;
      }
    };
  }, []);

  function setMergedImageUrl(blob) {
    if (mergedImageUrlRef.current) {
      URL.revokeObjectURL(mergedImageUrlRef.current);
    }
    const url = blob ? URL.createObjectURL(blob) : null;
    mergedImageUrlRef.current = url;
    setMergedImage(url);
  }

  // ── Merge stack via analysis-service ────────────────────────────────────────
  async function handleMergeStack(job) {
    setError(null);
    setMergedImageUrl(null);
    setMergeDetails(null);
    setSelectedJob(job);
    setMerging(true);
    try {
      if (!import.meta.env.VITE_ANALYSIS_URL) {
        const result = await authenticatedFetch(
          `/analysis/merge-stored-stack/${encodeURIComponent(job.id)}`, { method: 'POST' },
        );
        if (!result.ok) {
          const body = await result.text().catch(() => '');
          throw new Error(`Merge failed: ${result.status} ${body}`);
        }
        setMergeDetails({
          source: result.headers.get('X-Photographer-Stack-Source'),
          frames: Number(result.headers.get('X-Photographer-Stack-Frame-Count')),
        });
        setMergedImageUrl(await result.blob());
        return;
      }

      // Try to retrieve a cached merge result first
      const cachedRes = await fetch(`${ANALYSIS_BASE}/analysis/merge-result/${job.id}`, {
        credentials: 'include',
      });
      if (cachedRes.ok) {
        const blob = await cachedRes.blob();
        setMergedImageUrl(blob);
        setMerging(false);
        return;
      }

      // No cache — fetch the export ZIP and unpack it with JSZip
      const exportRes = await authenticatedFetch(`/jobs/${job.id}/export`);
      if (!exportRes.ok) {
        throw new Error(`Export failed: ${exportRes.statusText}`);
      }

      const zipArrayBuffer = await exportRes.arrayBuffer();
      setZipLibLoading(true);
      let JSZip;
      try {
        ({ default: JSZip } = await import('jszip'));
      } finally {
        // Clear the "Loading merge tools…" status as soon as the dynamic
        // import resolves (or fails), so the button label correctly
        // transitions to the unzip / upload / merge phase below instead of
        // staying on "Loading merge tools…" for the rest of the flow.
        setZipLibLoading(false);
      }
      const zip     = await JSZip.loadAsync(zipArrayBuffer);
      const entries = Object.values(zip.files).filter((f) => !f.dir);

      if (entries.length < 2) {
        throw new Error('At least 2 frames are required for merging');
      }

      const formData = new FormData();
      for (const entry of entries) {
        const bytes = await entry.async('blob');
        formData.append('frames', bytes, entry.name);
      }

      const mergeRes = await fetch(`${ANALYSIS_BASE}/analysis/merge-stack?jobId=${job.id}`, {
        method: 'POST',
        credentials: 'include',
        body: formData,
      });
      if (!mergeRes.ok) {
        const body = await mergeRes.text().catch(() => '');
        throw new Error(`Merge failed: ${mergeRes.status} ${body}`);
      }
      const mergedBlob = await mergeRes.blob();
      setMergedImageUrl(mergedBlob);
    } catch (err) {
      setError(`Merge error: ${err.message}`);
    } finally {
      // Defensive reset: if the dynamic import threw before reaching the
      // inner finally, the flag may still be true.
      setZipLibLoading(false);
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
      const blob = await requestBlob(`/jobs/${jobId}/export`);
      const url      = URL.createObjectURL(blob);
      const a        = document.createElement('a');
      a.href         = url;
      a.download     = `job-${jobId.substring(0, 8)}-frames.zip`;
      a.click();
      // Delay revocation to give the browser time to initiate the download
      setTimeout(() => URL.revokeObjectURL(url), 1000);
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

  // First frame image is served by the new /frames/{frameId}/image endpoint
  const firstFrameId = selectedJob?.frames?.[0];
  const beforeStyle  = firstFrameId
    ? {
        backgroundImage: `url(${apiUrl(`/jobs/${selectedJob.id}/frames/${firstFrameId}/image`)})`,
        backgroundSize: 'cover',
        backgroundPosition: 'center',
      }
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
                  disabled={merging || zipLibLoading}
                >
                  {(zipLibLoading && selectedJob?.id === job.id)
                    ? 'Loading merge tools…'
                    : (merging && selectedJob?.id === job.id ? 'Merging…' : 'Merge Stack')}
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
          {mergeDetails?.source === 'RAW' && <p className="card-label">Developed from camera RAW.</p>}
          {mergeDetails?.source === 'EMBEDDED_PREVIEW' && (
            <p className="card-label">Merged from embedded previews.</p>
          )}
          {mergeDetails?.frames > 0 && mergeDetails.frames < (selectedJob.frames?.length ?? 0) && (
            <p className="card-label">
              Used {mergeDetails.frames} of {selectedJob.frames.length} captured frames.
            </p>
          )}

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
            {/* "Before" — first captured frame served by /frames/{frameId}/image */}
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
                setMergedImageUrl(null);
                setSelectedJob(null);
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
