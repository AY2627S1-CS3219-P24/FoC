import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  createRouter,
  createMemoryHistory,
  RouterProvider,
} from '@tanstack/react-router'
import {
  render,
  screen,
  cleanup,
  act,
  fireEvent,
  waitFor,
} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AuthRequestError } from './authRequest'
import { beforeEach, afterEach, it, expect, vi } from 'vitest'

import { routeTree } from '#/routes'

import { refreshSession } from '../api/refreshSession.api'
import { logoutUser } from '../api/logoutUser.api'
import { clearSession, establishSession, ensureSession } from './authSession'
import type { AccessTokenResponse } from '../types/auth.types'

vi.mock('../api/logoutUser.api', () => ({ logoutUser: vi.fn() }))

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
  vi.mocked(logoutUser).mockReset()
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
})

afterEach(() => {
  cleanup()
  clients.forEach((client) => client.clear())
  clients.length = 0
  clearSession(true)
  vi.restoreAllMocks()
  vi.useRealTimers()
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
  'shows loading after 500ms and waits for recovery from %s',
  async (path) => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
    let resolve!: (value: AccessTokenResponse) => void
    refresh.mockReturnValue(
      new Promise((done) => {
        resolve = done
      }),
    )
    let router!: ReturnType<typeof setup>
    await act(async () => {
      router = setup(path)
    })

    expect(refresh).toHaveBeenCalledTimes(1)
    await act(async () => vi.advanceTimersByTimeAsync(499))
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(
      screen.queryByRole('heading', { name: 'Welcome to FoC' }),
    ).not.toBeInTheDocument()

    await act(async () => vi.advanceTimersByTimeAsync(1))
    expect(screen.getByRole('status')).toHaveTextContent('Loading…')
    expect(
      screen.queryByRole('heading', { name: 'Welcome to FoC' }),
    ).not.toBeInTheDocument()

    await act(async () => resolve(tokens()))

    expect(
      screen.getByRole('heading', { name: 'Welcome to FoC' }),
    ).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/app')
    expect(refresh).toHaveBeenCalledTimes(1)
  },
)

it('opens the app without showing loading when recovery finishes before 500ms', async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
  let resolve!: (value: AccessTokenResponse) => void
  refresh.mockReturnValue(
    new Promise((done) => {
      resolve = done
    }),
  )
  await act(async () => {
    setup('/app')
  })
  await act(async () => vi.advanceTimersByTimeAsync(100))
  expect(screen.queryByRole('status')).not.toBeInTheDocument()

  await act(async () => resolve(tokens()))

  expect(
    screen.getByRole('heading', { name: 'Welcome to FoC' }),
  ).toBeInTheDocument()
  await act(async () => vi.advanceTimersByTimeAsync(500))
  expect(screen.queryByRole('status')).not.toBeInTheDocument()
})

it('redirects to login when refresh returns 401', async () => {
  refresh.mockRejectedValue(new AuthRequestError(401))
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

it.each(['success', 'failure'])(
  'keeps the app unchanged until logout %s, then opens login',
  async (outcome) => {
    establishSession(tokens())
    let finish!: () => void
    vi.mocked(logoutUser).mockReturnValue(
      new Promise<void>((resolve, reject) => {
        finish = () =>
          outcome === 'success' ? resolve() : reject(new Error('offline'))
      }),
    )
    const router = setup('/app')
    const button = await screen.findByRole('button', { name: 'Log out' })
    fireEvent.click(button)
    fireEvent.click(button)
    await waitFor(() => expect(logoutUser).toHaveBeenCalledTimes(1))

    expect(button).toBeEnabled()
    expect(button).toHaveTextContent('Log out')
    expect(
      screen.getByRole('heading', { name: 'Welcome to FoC' }),
    ).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/app')

    await act(async () => finish())
    expect(
      await screen.findByRole('heading', { name: 'Welcome Back' }),
    ).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/login')
    expect(await ensureSession()).toBeNull()
    expect(refresh).not.toHaveBeenCalled()
  },
)
