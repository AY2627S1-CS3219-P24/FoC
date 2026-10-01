import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { RequesterHomePage } from './RequesterHomePage'

describe('RequesterHomePage', () => {
  it('shows the dashboard categories, favourite and recent errands', () => {
    render(<RequesterHomePage />)
    expect(
      screen.getByRole('heading', { name: 'What do you need?' }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('list', { name: 'Location categories' }).children,
    ).toHaveLength(7)
    expect(
      screen.getByRole('button', { name: /CoffeeBean @ COM3 Open now/ }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: /COM2 Ongoing/ }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: /COM2 Yesterday/ }),
    ).toBeInTheDocument()
  })

  it('opens a category preview and closes it with Escape', async () => {
    const user = userEvent.setup()
    render(<RequesterHomePage />)
    await user.click(screen.getByRole('button', { name: 'Drinks' }))
    expect(screen.getByRole('dialog', { name: 'Drinks' })).toBeInTheDocument()
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Drinks' })).toHaveFocus()
  })

  it('does not pretend to create an errand without the creation workflow', async () => {
    const user = userEvent.setup()
    render(<RequesterHomePage />)
    await user.click(screen.getByRole('button', { name: 'Create Errand' }))
    expect(
      screen.getByRole('dialog', { name: 'Create Errand' }),
    ).toHaveTextContent('No errand has been created.')
    await user.click(screen.getByRole('button', { name: 'Close' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})
