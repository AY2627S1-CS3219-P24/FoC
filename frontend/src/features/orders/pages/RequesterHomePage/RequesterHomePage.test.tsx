import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  RouterProvider,
} from '@tanstack/react-router'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { RequesterHomePage } from './RequesterHomePage'

const renderHome = () => {
  const rootRoute = createRootRoute()
  const homeRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/app',
    component: RequesterHomePage,
  })
  const locationsRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/locations',
    validateSearch: (search: Record<string, unknown>) => ({
      category:
        typeof search.category === 'string' ? search.category : undefined,
    }),
    component: () => <h1>Locations</h1>,
  })
  const router = createRouter({
    routeTree: rootRoute.addChildren([homeRoute, locationsRoute]),
    history: createMemoryHistory({ initialEntries: ['/app'] }),
  })
  render(<RouterProvider router={router} />)
  return router
}

describe('RequesterHomePage', () => {
  it('shows pictured categories and the sample recent errands', async () => {
    renderHome()

    await screen.findByRole('heading', { name: 'What do you need?' })
    const categories = screen.getByRole('list', {
      name: 'Location categories',
    })
    expect(categories.children).toHaveLength(5)
    expect(categories.querySelectorAll('img')).toHaveLength(5)
    expect(
      screen.getByRole('button', { name: /COM2 Ongoing/ }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: /COM2 Yesterday/ }),
    ).toBeInTheDocument()
  })

  it('navigates to locations with the selected category query', async () => {
    const user = userEvent.setup()
    const router = renderHome()
    await screen.findByRole('heading', { name: 'What do you need?' })

    await user.click(screen.getByRole('link', { name: 'Coffee' }))
    await screen.findByRole('heading', { name: 'Locations' })
    expect(router.state.location.pathname).toBe('/locations')
    expect(router.state.location.search).toEqual({ category: 'COFFEE' })
  })
})
