import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import EventCard from './EventCard';

const event = {
  id: 42,
  state: 'CLASSIFIED',
  timestamp: '2026-11-18T12:00:00Z',
  mode: 'WILDLIFE',
  triggerReason: 'motion',
  aiResult: { primaryLabel: 'Robin', confidence: 0.9, summary: 'A robin on a branch.' },
};

describe('EventCard', () => {
  afterEach(cleanup);

  it('offers a focusable action that opens the selected observation', () => {
    const openEvent = vi.fn();
    render(<EventCard event={event} onClick={openEvent} />);

    const action = screen.getByRole('button', { name: 'Robin' });
    action.focus();
    expect(action).toHaveFocus();
    fireEvent.click(action);
    expect(openEvent).toHaveBeenCalledExactlyOnceWith(event);
  });

  it('shows a read-only observation without an inactive action', () => {
    render(<EventCard event={event} />);

    expect(screen.getByRole('heading', { name: 'Robin' })).toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });
});
