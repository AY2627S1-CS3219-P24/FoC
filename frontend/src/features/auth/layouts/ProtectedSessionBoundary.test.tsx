import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  createRouter,
  createMemoryHistory,
  RouterProvider,
} from '@tanstack/react-router'
import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'

import { routeTree } from '#/routes'

import {
  clearSession,
  establishSession,
  ensureSession,
} from '../lib/authSession'
import { logoutUser } from '../api/logoutUser.api'
import { refreshSession } from '../api/refreshSession.api'

vi.mock('../api/logoutUser.api', () => ({ logoutUser: vi.fn() }))
vi.mock('../api/refreshSession.api', () => ({ refreshSession: vi.fn() }))

const clients: Array<QueryClient> = []

beforeEach(() => {
  clearSession(true)
  establishSession({
    accessToken: 'token',
    expiresAt: new Date(Date.now() + 300_000).toISOString(),
  })
  vi.mocked(logoutUser).mockReset()
  vi.mocked(refreshSession).mockReset()
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
})

afterEach(() => {
  cleanup()
  clients.forEach((client) => client.clear())
  clients.length = 0
  clearSession(true)
  vi.restoreAllMocks()
})

const setup = async () => {
  const client = new QueryClient()
  clients.push(client)
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: ['/app'] }),
  })
  render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  await screen.findByRole('heading', { name: 'Welcome to FoC' })
  return router
}

it('hides the page while logout is pending and navigates after the originating page unmounts', async () => {
  let resolve!: () => void
  vi.mocked(logoutUser).mockReturnValue(
    new Promise<void>((done) => {
      resolve = done
    }),
  )
  const router = await setup()
  await userEvent.setup().click(screen.getByRole('button', { name: 'Log out' }))

  expect(await screen.findByRole('status')).toHaveTextContent('Logging out')
  expect(
    screen.queryByRole('heading', { name: 'Welcome to FoC' }),
  ).not.toBeInTheDocument()
  expect(router.state.location.pathname).toBe('/app')

  await waitFor(() => expect(logoutUser).toHaveBeenCalledTimes(1))
  await act(async () => resolve())

  expect(
    await screen.findByRole('heading', { name: 'Welcome Back' }),
  ).toBeInTheDocument()
  expect(router.state.location.pathname).toBe('/login')

  await act(async () => {
    await router.navigate({ to: '/app' })
  })

  expect(router.state.location.pathname).toBe('/login')
  expect(refreshSession).not.toHaveBeenCalled()
})

it('keeps failed logout hidden across route checks and retries the same backend operation', async () => {
  vi.mocked(logoutUser)
    .mockRejectedValueOnce(new Error('Private details'))
    .mockResolvedValueOnce()
  const router = await setup()
  const user = userEvent.setup()
  await user.click(screen.getByRole('button', { name: 'Log out' }))

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Logout could not be confirmed',
  )
  expect(screen.queryByText('Private details')).not.toBeInTheDocument()
  expect(
    screen.queryByRole('heading', { name: 'Welcome to FoC' }),
  ).not.toBeInTheDocument()

  await act(async () => {
    await router.invalidate()
  })

  expect(router.state.location.pathname).toBe('/app')
  expect(await ensureSession()).toBeNull()

  await user.click(screen.getByRole('button', { name: 'Try again' }))

  expect(
    await screen.findByRole('heading', { name: 'Welcome Back' }),
  ).toBeInTheDocument()
  expect(logoutUser).toHaveBeenCalledTimes(2)
  expect(refreshSession).not.toHaveBeenCalled()
})

it('hides protected content and navigates when reauthentication is required', async () => {
  const router = await setup()
  act(() => clearSession())

  expect(
    screen.queryByRole('heading', { name: 'Welcome to FoC' }),
  ).not.toBeInTheDocument()
  expect(
    await screen.findByRole('heading', { name: 'Welcome Back' }),
  ).toBeInTheDocument()
  expect(router.state.location.pathname).toBe('/login')
  expect(refreshSession).not.toHaveBeenCalled()
})
