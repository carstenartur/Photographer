import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { request } from '../api/auth';
import FresnelIntegration from './FresnelIntegration';

vi.mock('../api/auth', () => ({ request: vi.fn() }));

describe('Fresnel integration', () => {
  beforeEach(() => request.mockReset());
  it('shows how to enable the integration before offering pairing', async () => {
    request.mockResolvedValue({ enabled: false, pairings: [] });
    render(<FresnelIntegration />);
    expect(await screen.findByText(/focusstack.fresnel.enabled/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Create pairing code' })).not.toBeInTheDocument();
  });
  it('uses the admin-confirmed URL and displays the single-use code', async () => {
    request.mockResolvedValueOnce({ enabled: true, descriptor: { displayName: 'Lab' }, pairings: [] })
      .mockResolvedValueOnce({ pairingCode: 'one-time-example', expiresAt: new Date(Date.now() + 60000).toISOString() });
    render(<FresnelIntegration />);
    fireEvent.change(await screen.findByLabelText('Photographer URL reachable from Fresnel'), {
      target: { value: 'https://photographer.example' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Create pairing code' }));
    expect(await screen.findByText('one-time-example')).toBeInTheDocument();
    expect(request.mock.calls[1][0]).toBe('/admin/integrations/fresnel/pairing-codes');
    expect(JSON.parse(request.mock.calls[1][1].body).baseUrl).toBe('https://photographer.example');
  });
});
