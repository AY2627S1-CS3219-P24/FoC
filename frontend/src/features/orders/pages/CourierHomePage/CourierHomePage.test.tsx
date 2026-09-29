import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { CourierHomePage } from './CourierHomePage'

describe('CourierHomePage', () => {
  it('filters sample errands by location and description and shows an empty state', async () => {
    const user = userEvent.setup()
    render(<CourierHomePage />)
    const search = screen.getByRole('searchbox', { name: 'Search errands' })
    await user.type(search, '  printed  ')
    expect(screen.getByText('PGP Foyer → UTown')).toBeInTheDocument()
    expect(
      screen.queryByText('CoffeeBean @ COM3 → COM2'),
    ).not.toBeInTheDocument()
    await user.clear(search)
    await user.type(search, 'com3')
    expect(screen.getByText('CoffeeBean @ COM3 → COM2')).toBeInTheDocument()
    await user.clear(search)
    await user.type(search, 'no matching place')
    expect(screen.getByRole('status')).toHaveTextContent('No errands match')
    await user.clear(search)
    expect(screen.getAllByRole('listitem')).toHaveLength(3)
  })

  it('opens sample details without offering acceptance and allows closing them', async () => {
    const user = userEvent.setup()
    render(<CourierHomePage />)
    await user.click(
      screen.getByRole('button', {
        name: 'View errand from PGP Foyer to UTown',
      }),
    )
    expect(screen.getByRole('dialog')).toHaveTextContent(
      'This is a sample errand',
    )
    expect(
      screen.queryByRole('button', { name: /accept/i }),
    ).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Close' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('keeps the own request non-actionable and mode switching disabled', () => {
    render(<CourierHomePage />)
    expect(screen.getByText('Not eligible')).toBeInTheDocument()
    expect(screen.getByText('PC Commons → AS8').closest('button')).toBeNull()
    expect(screen.getByRole('button', { name: /Courier/ })).toBeDisabled()
  })
})
