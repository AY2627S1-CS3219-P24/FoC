import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'
import { RequesterHomePage } from './RequesterHomePage'

const { navigate } = vi.hoisted(() => ({ navigate: vi.fn() }))
vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => navigate,
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
vi.mock('../../hooks/useOrders', () => ({
  useOrders: () => ({
    data: [],
    user: { id: 'me', name: 'Alex' },
    isPending: false,
    error: null,
    identityError: null,
  }),
  useOrderClock: () => 0,
}))
beforeEach(() => navigate.mockClear())

describe('RequesterHomePage', () => {
  it('shows the existing dashboard sections and creation link', () => {
    render(<RequesterHomePage />)
    expect(
      screen.getByRole('heading', { name: 'What do you need?' }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('list', { name: 'Location categories' }).children,
    ).toHaveLength(7)
    expect(
      screen.getByRole('heading', { name: 'Favourite locations' }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('heading', { name: 'Recent errands' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Create Errand' })).toHaveAttribute(
      'href',
      '/errands/new',
    )
  })
  it('opens the category preview and returns focus after closing', async () => {
    const user = userEvent.setup()
    render(<RequesterHomePage />)
    await user.click(screen.getByRole('button', { name: 'Drinks' }))
    expect(screen.getByRole('dialog', { name: 'Drinks' })).toBeInTheDocument()
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Drinks' })).toHaveFocus()
  })
  it('requests a switch to courier view', async () => {
    const user = userEvent.setup()
    render(<RequesterHomePage />)
    screen.getByRole('button', { name: 'Switch view: Requester' }).focus()
    await user.keyboard('{ArrowDown}')
    await user.click(await screen.findByRole('menuitem', { name: 'Courier' }))
    expect(navigate).toHaveBeenCalledWith({ to: '/courier' })
  })
})
