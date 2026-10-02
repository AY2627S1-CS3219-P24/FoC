import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  RouterProvider,
} from '@tanstack/react-router'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { listSuppliers } from '../../api/suppliers.api'
import type { Supplier, SupplierCategory } from '../../types/supplier.types'
import { SupplierCategoryPage } from './SupplierCategoryPage'

vi.mock('../../api/suppliers.api', async (importOriginal) => ({
  ...(await importOriginal<Record<string, unknown>>()),
  listSuppliers: vi.fn(),
}))

const coffeeSupplier: Supplier = {
  id: 'coffee',
  name: 'CoffeeBean @ COM3',
  category: 'COFFEE',
  building: 'COM3',
  floor: '1',
  locationDescription: null,
  latitude: null,
  longitude: null,
  openingHours: [
    {
      dayOfWeek: 'MONDAY',
      opensAt: '09:00:00',
      closesAt: '20:00:00',
    },
  ],
  imageUrl: null,
  active: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

const listSuppliersMock = vi.mocked(listSuppliers)
let queryClient: QueryClient

const renderPage = (path = '/locations?category=COFFEE') => {
  queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const rootRoute = createRootRoute()
  const locationsRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/locations',
    validateSearch: (
      search: Record<string, unknown>,
    ): { category?: SupplierCategory } => ({
      category:
        typeof search.category === 'string'
          ? (search.category as SupplierCategory)
          : undefined,
    }),
    component: SupplierCategoryPage,
  })
  const appRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/app',
    component: () => <h1>Home</h1>,
  })
  const router = createRouter({
    routeTree: rootRoute.addChildren([locationsRoute, appRoute]),
    history: createMemoryHistory({ initialEntries: [path] }),
  })

  return render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
}

describe('SupplierCategoryPage', () => {
  beforeEach(() => {
    listSuppliersMock.mockResolvedValue([coffeeSupplier])
  })

  afterEach(() => {
    queryClient.clear()
    vi.clearAllMocks()
  })

  it('loads suppliers for the category query and shows details', async () => {
    const user = userEvent.setup()
    renderPage()

    expect(
      await screen.findByRole('heading', { name: 'Coffee' }),
    ).toBeInTheDocument()
    expect(listSuppliersMock).toHaveBeenCalledWith({ category: ['COFFEE'] })
    const supplier = await screen.findByRole('button', {
      name: /CoffeeBean @ COM3/,
    })
    expect(supplier).toHaveTextContent('COM3, Level 1')
    expect(supplier).toHaveTextContent('Mon 09:00 – 20:00')

    await user.click(supplier)
    expect(
      screen.getByRole('dialog', { name: 'CoffeeBean @ COM3' }),
    ).toHaveTextContent('COM3, Level 1. Mon 09:00 – 20:00.')
  })

  it('shows loading, error and empty states', async () => {
    listSuppliersMock.mockReturnValueOnce(new Promise(() => {}))
    const firstRender = renderPage()
    expect(await screen.findByRole('status')).toHaveTextContent(
      'Loading coffee suppliers…',
    )
    firstRender.unmount()
    queryClient.clear()

    listSuppliersMock.mockRejectedValueOnce(new Error('Unavailable'))
    const secondRender = renderPage()
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Unable to load suppliers. Please try again.',
    )
    secondRender.unmount()
    queryClient.clear()

    listSuppliersMock.mockResolvedValueOnce([])
    renderPage()
    expect(
      await screen.findByText('No coffee suppliers found.'),
    ).toBeInTheDocument()
  })
})
