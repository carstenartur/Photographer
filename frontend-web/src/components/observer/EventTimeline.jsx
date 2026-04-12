/**
 * Displays a horizontal event state timeline.
 */
const STATES = [
  'IDLE',
  'MOTION_DETECTED',
  'TRACKING',
  'CAPTURING',
  'SELECTING',
  'ANALYZING',
  'COMPLETED',
];

const STATE_LABELS = {
  IDLE: 'Idle',
  MOTION_DETECTED: 'Motion',
  TRACKING: 'Track',
  CAPTURING: 'Capture',
  SELECTING: 'Select',
  ANALYZING: 'AI',
  COMPLETED: 'Done',
  DISCARDED: 'Discard',
};

export default function EventTimeline({ currentState }) {
  const stateIndex = STATES.indexOf(currentState);

  return (
    <div className="event-timeline">
      {STATES.map((state, idx) => {
        let className = 'timeline-step';
        if (idx < stateIndex) className += ' timeline-step-done';
        if (idx === stateIndex) className += ' timeline-step-active';
        if (currentState === 'DISCARDED' && idx === stateIndex) className += ' timeline-step-discarded';

        return (
          <div key={state} className={className}>
            <div className="timeline-dot" />
            <span className="timeline-label">{STATE_LABELS[state]}</span>
            {idx < STATES.length - 1 && <div className="timeline-line" />}
          </div>
        );
      })}
      {currentState === 'DISCARDED' && (
        <div className="timeline-step timeline-step-discarded timeline-step-active">
          <div className="timeline-dot" />
          <span className="timeline-label">{STATE_LABELS.DISCARDED}</span>
        </div>
      )}
    </div>
  );
}
