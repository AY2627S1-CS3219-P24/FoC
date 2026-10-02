import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  RouterProvider,
} from '@tanstack/react-router'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useMyErrands } from '../../hooks/useOrders'
import { orderFixture } from '../../test/orderFixtures'
import { RequesterHomePage } from './RequesterHomePage'

vi.mock('../../hooks/useOrders', () => ({
  useMyErrands: vi.fn(),
  useOrderClock: () => Date.parse('2030-01-01T00:10:00Z'),
}))

const queryMock = vi.mocked(useMyErrands, { partial: true })
const refetch = vi.fn()
const emptyMessage =
  'No errands yet. Create your first request or switch to Courier to help someone.'

type QueryResult = Pick<
  ReturnType<typeof useMyErrands>,
  'data' | 'userId' | 'isPending' | 'error' | 'refetch'
>

const emptyResult = {
  data: { items: [], total: 0, page: 0, size: 3 },
  userId: 'me',
  isPending: false,
  error: null,
  refetch,
} satisfies QueryResult

const renderPage = async () => {
  const root = createRootRoute()
  const home = createRoute({
    getParentRoute: () => root,
    path: '/home',
    component: RequesterHomePage,
  })
  const locations = createRoute({
    getParentRoute: () => root,
    path: '/locations',
    validateSearch: (search: Record<string, unknown>) => ({
      category:
        typeof search.category === 'string' ? search.category : undefined,
    }),
    component: () => <h1>Locations</h1>,
  })
  const router = createRouter({
    routeTree: root.addChildren([home, locations]),
    history: createMemoryHistory({ initialEntries: ['/home'] }),
  })
  render(<RouterProvider router={router} />)
  await screen.findByRole('heading', { name: 'What do you need?' })
  return router
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
  queryMock.mockReturnValue(emptyResult)
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('RequesterHomePage', () => {
  it('shows the requester dashboard and a link to create an errand', async () => {
    await renderPage()

    expect(screen.getByRole('button', { name: 'Requester' })).toBeEnabled()
    expect(
      screen.getByRole('heading', { name: 'Recent errands' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Create Errand' })).toHaveAttribute(
      'href',
      '/errands/new',
    )
    expect(
      within(
        screen.getByRole('list', { name: 'Location categories' }),
      ).getAllByRole('link'),
    ).toHaveLength(5)
    expect(queryMock).toHaveBeenCalledWith({
      as: 'all',
      view: 'all',
      page: 0,
      size: 3,
    })
  })

  it.each([
    ['Food', 'FOOD'],
    ['Coffee', 'COFFEE'],
    ['Shopping', 'SHOPPING'],
    ['Printing', 'PRINTING'],
    ['Other', 'OTHER'],
  ])('links %s to its supplier category', async (label, category) => {
    await renderPage()

    expect(screen.getByRole('link', { name: label })).toHaveAttribute(
      'href',
      `/locations?category=${category}`,
    )
  })

  it('navigates to locations with the selected category', async () => {
    const user = userEvent.setup()
    const router = await renderPage()

    await user.click(screen.getByRole('link', { name: 'Coffee' }))

    expect(
      await screen.findByRole('heading', { name: 'Locations' }),
    ).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/locations')
    expect(router.state.location.search).toEqual({ category: 'COFFEE' })
  })

  it('offers navigation to courier mode', async () => {
    const user = userEvent.setup()
    await renderPage()

    await user.click(screen.getByRole('button', { name: 'Requester' }))

    expect(
      await screen.findByRole('menuitem', { name: 'Courier' }),
    ).toHaveAttribute('href', '/home?mode=courier')
  })

  it('shows an empty state when there are no recent errands', async () => {
    await renderPage()

    expect(screen.getByText(emptyMessage)).toBeInTheDocument()
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('shows recent requested and delivered errands with links to their details', async () => {
    queryMock.mockReturnValue({
      ...emptyResult,
      data: {
        items: [
          orderFixture({
            id: 'requested',
            requester: { id: 'me', name: 'Alex' },
          }),
          orderFixture({
            id: 'delivering',
            courier: { id: 'me', name: 'Alex' },
            status: 'ACCEPTED',
            pickup: { name: 'Print Shop', address: 'COM3' },
            deliveryLocation: 'COM2',
          }),
        ],
        total: 2,
        page: 0,
        size: 3,
      },
    })
    await renderPage()

    const recent = within(
      screen.getByRole('region', { name: 'Recent errands' }),
    )
    expect(recent.getAllByRole('listitem')).toHaveLength(2)
    const requested = recent.getByRole('link', {
      name: /Campus Cafe → Library/,
    })
    expect(requested).toHaveAttribute(
      'href',
      '/errands/requested?tab=active&participation=all&page=0&posted=false',
    )
    expect(requested).toHaveTextContent('Requested by you')
    expect(requested).toHaveTextContent('5 credits')
    expect(requested).toHaveTextContent('Expires in 50 min')
    const delivering = recent.getByRole('link', { name: /Print Shop → COM2/ })
    expect(delivering).toHaveAttribute(
      'href',
      '/errands/delivering?tab=active&participation=all&page=0&posted=false',
    )
    expect(delivering).toHaveTextContent("You're delivering")
    expect(delivering).toHaveTextContent('ACCEPTED')
    expect(screen.queryByText(emptyMessage)).not.toBeInTheDocument()
  })

  it('shows loading feedback without an empty state', async () => {
    queryMock.mockReturnValue({
      ...emptyResult,
      data: undefined,
      isPending: true,
    })
    await renderPage()

    expect(screen.getByRole('status')).toHaveTextContent('Loading errands…')
    expect(screen.queryByText(emptyMessage)).not.toBeInTheDocument()
    expect(
      within(
        screen.getByRole('region', { name: 'Recent errands' }),
      ).queryByRole('list'),
    ).not.toBeInTheDocument()
  })

  it('shows an error and retries loading recent errands', async () => {
    const user = userEvent.setup()
    queryMock.mockReturnValue({
      ...emptyResult,
      error: new Error('Unavailable'),
    })
    await renderPage()

    expect(screen.getByRole('alert')).toHaveTextContent(
      'Unable to complete the request. Please try again.',
    )
    expect(screen.queryByText(emptyMessage)).not.toBeInTheDocument()
    expect(
      within(
        screen.getByRole('region', { name: 'Recent errands' }),
      ).queryByRole('list'),
    ).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Try again' }))

    expect(refetch).toHaveBeenCalledOnce()
  })
})
