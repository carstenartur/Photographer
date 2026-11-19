import './DetectionOverlay.css';

function boxStyle({ x, y, width, height }) {
  return {
    left: x * 100 + '%',
    top: y * 100 + '%',
    width: width * 100 + '%',
    height: height * 100 + '%',
  };
}

export default function DetectionOverlay({ boxes = [], region }) {
  return (
    <div className="observer-detection-overlay">
      {region && <span className="observer-region-outline" style={boxStyle(region)} aria-hidden="true" />}
      {boxes.map((box, index) => (
        <span className="observer-detection-box" key={index} style={boxStyle(box)}>
          <span>{box.label} {Math.round(box.confidence * 100)}%</span>
        </span>
      ))}
    </div>
  );
}
