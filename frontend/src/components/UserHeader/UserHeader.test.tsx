import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { UserHeader } from './UserHeader'

const matchMedia = window.matchMedia

afterEach(() => {
  window.matchMedia = matchMedia
})

const useNarrowScreen = () => {
  window.matchMedia = ((media: string) => ({
    media,
    matches: media === '(max-width: 700px)',
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  })) as typeof window.matchMedia
}

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

  it('opens the page menu on a narrow screen and closes it from a link or Escape', async () => {
    useNarrowScreen()
    const user = userEvent.setup()
    render(
      <UserHeader
        name="Alex Tan"
        availableCredits={12}
        onAccountClick={vi.fn()}
        navigation={
          <a href="/home" aria-current="page">
            Home
          </a>
        }
      />,
    )
    const trigger = screen.getByRole('button', { name: 'Open menu' })
    await user.click(trigger)
    const menu = screen.getByRole('navigation', { name: 'Main navigation' })
    const home = within(menu).getByRole('link', { name: 'Home' })
    expect(home).toHaveAttribute('aria-current', 'page')
    expect(home.querySelector('svg')).toBeInTheDocument()
    expect(trigger).toHaveAttribute('aria-expanded', 'true')
    await user.click(home)
    expect(
      screen.queryByRole('navigation', { name: 'Main navigation' }),
    ).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Open menu' }))
    await user.keyboard('{Escape}')
    expect(
      screen.queryByRole('navigation', { name: 'Main navigation' }),
    ).not.toBeInTheDocument()
  })

  it('shows the credit line in the account menu on a narrow screen', async () => {
    useNarrowScreen()
    const user = userEvent.setup()
    render(
      <UserHeader
        name="Alex Tan"
        availableCredits={12}
        onAccountClick={vi.fn()}
        navigation={null}
      />,
    )
    await user.click(
      screen.getByRole('button', { name: 'Open account for Alex Tan' }),
    )
    const menu = screen.getByRole('menu')
    expect(within(menu).getByText('12 credits')).toBeInTheDocument()
  })

  it('keeps the page menu free of a backdrop on the map', async () => {
    useNarrowScreen()
    const user = userEvent.setup()
    render(
      <UserHeader
        overlay
        name="Alex Tan"
        navigation={<a href="/map">Map</a>}
      />,
    )
    await user.click(screen.getByRole('button', { name: 'Open menu' }))
    const menu = screen.getByRole('navigation', { name: 'Main navigation' })
    expect(menu.className).toContain('navPanel')
    expect(document.querySelector('[role="presentation"]')).toBeNull()
    expect(within(menu).getByRole('link', { name: 'Map' })).toBeInTheDocument()
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
