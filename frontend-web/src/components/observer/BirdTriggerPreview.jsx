import { useRef, useState } from 'react';
import DetectionOverlay from './DetectionOverlay';

function positionOnFrame(event) {
  const { left, top, width, height } = event.currentTarget.getBoundingClientRect();
  const clamp = (value) => Math.round(Math.max(0, Math.min(1, value)) * 100000) / 100000;
  return {
    x: clamp((event.clientX - left) / width),
    y: clamp((event.clientY - top) / height),
  };
}

function regionBetween(start, end) {
  const x = Math.min(start.x, end.x);
  const y = Math.min(start.y, end.y);
  return {
    x,
    y,
    width: Number(Math.abs(start.x - end.x).toFixed(5)),
    height: Number(Math.abs(start.y - end.y).toFixed(5)),
  };
}

export default function BirdTriggerPreview({ preview, roi, onRegionChange, canEdit, running }) {
  const drag = useRef(null);
  const [drawing, setDrawing] = useState(false);
  const [drawnRegion, setDrawnRegion] = useState(null);
  const drawingEnabled = drawing && canEdit;

  function startDrag(event) {
    if (!drawingEnabled || drag.current || event.button !== 0) return;
    event.preventDefault();
    event.currentTarget.setPointerCapture?.(event.pointerId);
    drag.current = { pointer: event.pointerId, origin: positionOnFrame(event) };
  }

  function moveDrag(event) {
    if (!drawingEnabled || drag.current?.pointer !== event.pointerId) return;
    setDrawnRegion(regionBetween(drag.current.origin, positionOnFrame(event)));
  }

  function endDrag(event) {
    if (drag.current?.pointer !== event.pointerId) return;
    const region = regionBetween(drag.current.origin, positionOnFrame(event));
    drag.current = null;
    setDrawnRegion(null);
    if (drawingEnabled && region.width > 0 && region.height > 0) {
      setDrawing(false);
      onRegionChange(region);
    }
  }

  function cancelDrag(event) {
    if (drag.current?.pointer !== event.pointerId) return;
    drag.current = null;
    setDrawnRegion(null);
  }

  if (!preview?.imageData) {
    return (
      <div className="bird-trigger-preview-placeholder">
        {running ? 'Waiting for an analysed camera frame…' : 'Start preview to see the camera frame.'}
      </div>
    );
  }

  return (
    <div className="bird-trigger-preview">
      <div
        className={drawingEnabled ? 'bird-trigger-frame bird-trigger-frame-drawing' : 'bird-trigger-frame'}
        onPointerDown={startDrag}
        onPointerMove={moveDrag}
        onPointerUp={endDrag}
        onPointerCancel={cancelDrag}
        onLostPointerCapture={cancelDrag}
      >
        <img
          src={'data:image/jpeg;base64,' + preview.imageData}
          alt="Latest analysed camera frame"
          width={preview.width}
          height={preview.height}
          draggable={false}
        />
        <DetectionOverlay boxes={preview.boundingBoxes ?? []} region={drawnRegion || roi} />
      </div>
      <div className="bird-trigger-preview-actions">
        <button
          type="button"
          className="btn btn-small"
          disabled={!canEdit}
          onClick={() => setDrawing(!drawing)}
        >
          {drawing ? 'Cancel drawing' : 'Draw region'}
        </button>
        {drawingEnabled && <small>Drag across the part of the frame to monitor.</small>}
      </div>
    </div>
  );
}
