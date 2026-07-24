import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  createAstroSession,
  listAstroSessions,
} from '../api/astroApi'
import AstroSessions from './AstroSessions'

vi.mock('../api/astroApi', () => ({
  createAstroSession: vi.fn(),
  getAstroSession: vi.fn(),
  listAstroSessions: vi.fn(),
  abortAstroSession: vi.fn(),
  deleteAstroSession: vi.fn(),
  downloadAstroResultImage: vi.fn(),
}))

const SESSION = {
  id: '12345678-1234-1234-1234-123456789abc',
  status: 'STACKING',
  createdAt: '2026-07-23T20:00:00Z',
  captureConfig: {
    frameCount: 8,
    stackingConfig: {
      mode: 'SIGMA_CLIPPED_MEAN',
      sigmaThreshold: 1.8,
      maxIterations: 4,
      minimumSamples: 5,
    },
  },
  focusConfig: { strategy: 'MANUAL_FIXED' },
  stackingProgressPercent: 37,
}

afterEach(() => {
  cleanup()
})

describe('AstroSessions robust stacking', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listAstroSessions.mockResolvedValue([])
    createAstroSession.mockResolvedValue({
      ...SESSION,
      status: 'PREPARING',
      stackingProgressPercent: 0,
    })
  })

  it('submits an explicit numeric sigma-clipped configuration', async () => {
    render(<AstroSessions />)

    fireEvent.click(screen.getByRole('button', { name: /new session/i }))
    fireEvent.change(screen.getByLabelText(/algorithm/i), {
      target: { value: 'SIGMA_CLIPPED_MEAN' },
    })
    fireEvent.change(screen.getByLabelText(/sigma threshold/i), {
      target: { value: '1.8' },
    })
    fireEvent.change(screen.getByLabelText(/maximum iterations/i), {
      target: { value: '4' },
    })
    fireEvent.change(screen.getByLabelText(/minimum samples/i), {
      target: { value: '5' },
    })
    fireEvent.click(screen.getByRole('button', { name: /start astro session/i }))

    await waitFor(() => expect(createAstroSession).toHaveBeenCalledTimes(1))
    expect(createAstroSession).toHaveBeenCalledWith(expect.objectContaining({
      captureConfig: expect.objectContaining({
        stackingConfig: {
          mode: 'SIGMA_CLIPPED_MEAN',
          sigmaThreshold: 1.8,
          maxIterations: 4,
          minimumSamples: 5,
        },
      }),
    }))
  })

  it('does not coerce an empty sigma value to zero', async () => {
    render(<AstroSessions />)

    fireEvent.click(screen.getByRole('button', { name: /new session/i }))
    fireEvent.change(screen.getByLabelText(/algorithm/i), {
      target: { value: 'SIGMA_CLIPPED_MEAN' },
    })
    const sigma = screen.getByLabelText(/sigma threshold/i)
    expect(sigma).toBeRequired()
    fireEvent.change(sigma, { target: { value: '' } })
    fireEvent.submit(sigma.closest('form'))

    expect(await screen.findByText(/enter valid stacking parameters/i)).toBeInTheDocument()
    expect(createAstroSession).not.toHaveBeenCalled()
  })

  it('shows persisted algorithm provenance and live stacking progress', async () => {
    listAstroSessions.mockResolvedValue([SESSION])

    render(<AstroSessions />)

    const sessionCell = await screen.findByText('12345678…')
    fireEvent.click(sessionCell)

    expect(screen.getAllByText('SIGMA_CLIPPED_MEAN').length).toBeGreaterThan(0)
    const progress = screen.getByRole('progressbar', { name: /stacking progress/i })
    expect(progress).toHaveAttribute('value', '37')
    expect(progress).toHaveAttribute('max', '100')
  })
})
