import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import LoginPage from './LoginPage'

vi.mock('../components/useAuth', () => ({
  useAuth: () => ({
    login: vi.fn(),
  }),
}))

describe('LoginPage', () => {
  it('renders username input', () => {
    render(<LoginPage />)
    expect(screen.getByLabelText(/username/i)).toBeInTheDocument()
  })
})
