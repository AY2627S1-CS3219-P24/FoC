import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  createRouter,
  createMemoryHistory,
  RouterProvider,
} from '@tanstack/react-router'
import { render, screen, cleanup, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError, AxiosHeaders } from 'axios'
import { beforeEach, afterEach, it, expect, vi } from 'vitest'

import { routeTree } from '#/routes'

import { refreshSession } from '../api/refreshSession.api'
import { clearSession } from './authSession'
import type { AccessTokenResponse } from '../types/auth.types'

vi.mock('../api/refreshSession.api', () => ({ refreshSession: vi.fn() }))

const refresh = vi.mocked(refreshSession)
const clients: Array<QueryClient> = []

const tokens = (): AccessTokenResponse => ({
  accessToken: 'token',
  expiresAt: new Date(Date.now() + 300_000).toISOString(),
})

beforeEach(() => {
  clearSession(true)
  refresh.mockReset()
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
})

afterEach(() => {
  cleanup()
  clients.forEach((client) => client.clear())
  clients.length = 0
  clearSession(true)
  vi.restoreAllMocks()
})

const setup = (path: string) => {
  const client = new QueryClient()
  clients.push(client)
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: [path] }),
  })
  render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

it.each(['/app', '/'])(
  'waits for recovery before showing protected content from %s',
  async (path) => {
    let resolve!: (value: AccessTokenResponse) => void
    refresh.mockReturnValue(
      new Promise((done) => {
        resolve = done
      }),
    )
    const router = setup(path)

    expect(await screen.findByRole('status')).toHaveTextContent(
      'Restoring your session',
    )
    expect(
      screen.queryByRole('heading', { name: 'Welcome to FoC' }),
    ).not.toBeInTheDocument()

    await act(async () => resolve(tokens()))

    expect(
      await screen.findByRole('heading', { name: 'Welcome to FoC' }),
    ).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/app')
    expect(refresh).toHaveBeenCalledTimes(1)
  },
)

it('redirects to login when refresh returns 401', async () => {
  refresh.mockRejectedValue(
    new AxiosError('Unauthorized', undefined, undefined, undefined, {
      status: 401,
      statusText: '',
      data: {},
      headers: {},
      config: { headers: new AxiosHeaders() },
    }),
  )
  const router = setup('/app')

  expect(
    await screen.findByRole('heading', { name: 'Welcome Back' }),
  ).toBeInTheDocument()
  expect(router.state.location.pathname).toBe('/login')
})

it('shows a safe failure and reruns beforeLoad on retry', async () => {
  refresh
    .mockRejectedValueOnce(new Error('Private server details'))
    .mockResolvedValueOnce(tokens())
  const router = setup('/app')

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Unable to restore your session',
  )
  expect(screen.queryByText('Private server details')).not.toBeInTheDocument()
  expect(
    screen.queryByRole('heading', { name: 'Welcome to FoC' }),
  ).not.toBeInTheDocument()
  expect(router.state.location.pathname).toBe('/app')

  await userEvent
    .setup()
    .click(screen.getByRole('button', { name: 'Try again' }))

  expect(
    await screen.findByRole('heading', { name: 'Welcome to FoC' }),
  ).toBeInTheDocument()
  expect(refresh).toHaveBeenCalledTimes(2)
})
