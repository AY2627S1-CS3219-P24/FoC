import { act, render, screen, within } from '@testing-library/react'
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  RouterProvider,
} from '@tanstack/react-router'
import { describe, expect, it } from 'vitest'
import { UserLayout } from './UserLayout'

describe('UserLayout', () => {
  it('shares one header and account across requester and courier child routes', async () => {
    const root = createRootRoute()
    const layout = createRoute({
      getParentRoute: () => root,
      id: 'user',
      component: () => (
        <UserLayout
          name="Alex Tan"
          availableCredits={15}
          navigation={<span>Home</span>}
        />
      ),
    })
    const requester = createRoute({
      getParentRoute: () => layout,
      path: '/requester',
      component: () => <h1>What do you need?</h1>,
    })
    const courier = createRoute({
      getParentRoute: () => layout,
      path: '/courier',
      component: () => <h1>Find an errand</h1>,
    })
    const router = createRouter({
      routeTree: root.addChildren([layout.addChildren([requester, courier])]),
      history: createMemoryHistory({ initialEntries: ['/requester'] }),
    })
    render(<RouterProvider router={router} />)
    await screen.findByRole('heading', { name: 'What do you need?' })
    const header = screen.getByRole('banner')
    expect(within(header).getByText('Alex Tan')).toBeInTheDocument()

    await act(async () => {
      router.history.push('/courier')
      await router.load()
    })

    expect(
      await screen.findByRole('heading', { name: 'Find an errand' }),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('heading', { name: 'What do you need?' }),
    ).not.toBeInTheDocument()
    expect(screen.getByRole('banner')).toBe(header)
    expect(within(header).getByText('15 credits')).toBeInTheDocument()
    expect(screen.getAllByRole('main')).toHaveLength(1)
    expect(
      screen.getByRole('link', { name: 'Skip to content' }),
    ).toHaveAttribute('href', '#user-content')
  })
})
