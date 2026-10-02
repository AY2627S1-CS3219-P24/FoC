import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { UserHeader } from './UserHeader'

describe('UserHeader', () => {
  it('renders supplied navigation and preserves a zero credit balance', () => {
    render(
      <UserHeader
        name="Alex Tan"
        availableCredits={0}
        navigation={
          <a href="/home" aria-current="page">
            Home
          </a>
        }
      />,
    )
    expect(
      screen.getByRole('navigation', { name: 'Main navigation' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Home' })).toHaveAttribute(
      'aria-current',
      'page',
    )
    expect(screen.getByText('0 credits')).toBeInTheDocument()
    expect(screen.getByText('AT')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Notifications' })).toBeDisabled()
  })

  it('invokes supplied account and notification actions with keyboard support', async () => {
    const user = userEvent.setup()
    const account = vi.fn()
    const notifications = vi.fn()
    render(
      <UserHeader
        name="Alex Tan"
        navigation={null}
        hasUnreadNotifications
        onAccountClick={account}
        onNotificationsClick={notifications}
      />,
    )
    await user.tab()
    await user.keyboard('{Enter}')
    expect(notifications).toHaveBeenCalledOnce()
    await user.tab()
    await user.keyboard('{Enter}')
    expect(account).not.toHaveBeenCalled()
    await user.click(await screen.findByRole('menuitem', { name: 'Profile' }))
    expect(account).toHaveBeenCalledOnce()
    expect(screen.getByText('Balance unavailable')).toBeInTheDocument()
  })

  it('opens the account menu with the keyboard, dismisses it, and logs out', async () => {
    const user = userEvent.setup()
    const logout = vi.fn()
    render(
      <UserHeader
        name="Alex Tan"
        navigation={null}
        onAccountClick={vi.fn()}
        onLogout={logout}
      />,
    )
    const trigger = screen.getByRole('button', {
      name: 'Open account for Alex Tan',
    })
    await user.tab()
    await user.keyboard('{Enter}')
    expect(
      screen.getByRole('menuitem', { name: 'Profile' }),
    ).toBeInTheDocument()
    expect(trigger).toHaveAttribute('aria-expanded', 'true')
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
    expect(trigger).toHaveFocus()
    await user.click(trigger)
    await user.click(await screen.findByRole('menuitem', { name: 'Log out' }))
    expect(logout).toHaveBeenCalledOnce()
  })

  it('shows an admin action only when one is supplied', async () => {
    const user = userEvent.setup()
    const admin = vi.fn()
    const { rerender } = render(
      <UserHeader name="Alex Tan" navigation={null} onAccountClick={vi.fn()} />,
    )
    await user.click(
      screen.getByRole('button', { name: 'Open account for Alex Tan' }),
    )
    expect(
      screen.queryByRole('menuitem', { name: 'Admin' }),
    ).not.toBeInTheDocument()

    rerender(
      <UserHeader
        name="Alex Tan"
        navigation={null}
        onAccountClick={vi.fn()}
        onAdminClick={admin}
      />,
    )
    await user.click(await screen.findByRole('menuitem', { name: 'Admin' }))
    expect(admin).toHaveBeenCalledOnce()
  })

  it('falls back to initials for a failed avatar and tries a replacement URL', () => {
    const { container, rerender } = render(
      <UserHeader name="Alex Tan" navigation={null} avatarUrl="/first.png" />,
    )
    fireEvent.error(screen.getByAltText(''))
    expect(screen.getByText('AT')).toBeInTheDocument()
    rerender(
      <UserHeader name="Alex Tan" navigation={null} avatarUrl="/second.png" />,
    )
    expect(container.querySelector('img')).toHaveAttribute('src', '/second.png')
  })
})
