import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { CourierHomePage } from './CourierHomePage'
import { orderFixture } from '../../test/orderFixtures'

const mocks = vi.hoisted(() => ({
  navigate: vi.fn(),
  query: vi.fn(),
  search: { mode: 'courier', q: '', page: 0 },
}))
vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => mocks.navigate,
  useSearch: () => mocks.search,
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
  errandKeys: { user: (id: string) => ['errands', id] },
  useAvailableOrders: (...args: Array<unknown>) => mocks.query(...args),
  useOrderClock: () => Date.parse('2030-01-01T00:10:00Z'),
}))
beforeEach(() => {
  mocks.navigate.mockReset()
  mocks.search = { mode: 'courier', q: '', page: 0 }
  mocks.query.mockReturnValue({
    data: {
      items: [orderFixture({ requester: { id: 'other', name: 'Sam' } })],
      total: 1,
      page: 0,
      size: 20,
    },
    userId: 'me',
    isPending: false,
    error: null,
  })
})
describe('CourierHomePage', () => {
  it('requests server filtering and debounces search into the URL', async () => {
    render(
      <QueryClientProvider client={new QueryClient()}>
        <CourierHomePage />
      </QueryClientProvider>,
    )
    expect(mocks.query).toHaveBeenCalledWith('', 0)
    expect(screen.getByRole('button', { name: 'Courier' })).toBeEnabled()
    await userEvent.setup().type(screen.getByRole('searchbox'), 'printed')
    await waitFor(() =>
      expect(mocks.navigate).toHaveBeenCalledWith(
        expect.objectContaining({
          search: { mode: 'courier', q: 'printed', page: 0 },
        }),
      ),
    )
    expect(
      screen.queryByText('Your request · Cannot accept'),
    ).not.toBeInTheDocument()
  })
  it('shows a server-provided empty search result', () => {
    mocks.search.q = 'no matches'
    mocks.query.mockReturnValue({
      data: { items: [], total: 0 },
      userId: 'me',
      isPending: false,
    })
    render(
      <QueryClientProvider client={new QueryClient()}>
        <CourierHomePage />
      </QueryClientProvider>,
    )
    expect(mocks.query).toHaveBeenCalledWith('no matches', 0)
    expect(screen.getByRole('status')).toHaveTextContent('No errands match')
  })
})
