import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'
import { CourierHomePage } from './CourierHomePage'

vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => vi.fn(),
  Link: ({
    to,
    children,
    className,
  }: {
    to: string
    children: ReactNode
    className?: string
  }) => (
    <a href={to} className={className}>
      {children}
    </a>
  ),
}))
vi.mock('../../hooks/useOrders', async () => {
  const { seedOrders } = await import('../../mocks/orders.mock')
  const now = Date.parse('2026-10-15T00:00:00Z')
  const user = { id: 'me', name: 'Alex' }
  const data = seedOrders(user, now)
  return {
    useOrders: () => ({
      data,
      user,
      isPending: false,
      error: null,
      identityError: null,
    }),
    useOrderClock: () => now,
  }
})

describe('CourierHomePage', () => {
  it('filters displayed open errands and shows an empty search result', async () => {
    const user = userEvent.setup()
    render(<CourierHomePage />)
    expect(screen.getAllByRole('listitem')).toHaveLength(2)
    const search = screen.getByRole('searchbox', { name: 'Search errands' })
    await user.type(search, '  printed  ')
    expect(screen.getAllByRole('listitem')).toHaveLength(1)
    expect(screen.getByText('Collect 20 printed pages')).toBeInTheDocument()
    await user.clear(search)
    await user.type(search, 'no matching place')
    expect(screen.getByRole('status')).toHaveTextContent('No errands match')
  })
  it('identifies the current user’s own request', () => {
    render(<CourierHomePage />)
    expect(screen.getByText('Your request · Cannot accept')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Deliver' })).toBeEnabled()
  })
})
