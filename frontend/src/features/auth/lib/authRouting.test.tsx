import { AuthRouterProvider } from '#/App'
import { AuthProvider } from '#/features/auth/providers/AuthProvider'
import { AxiosError } from 'axios'
import type { InternalAxiosRequestConfig } from 'axios'
import { axiosClient } from '#/lib/axiosClient'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createRouter, createMemoryHistory } from '@tanstack/react-router'
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
import { setAccessToken, getAccessToken } from './accessTokenStore'
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
  setAccessToken(null)
  refresh.mockReset()
  vi.mocked(logoutUser).mockReset()
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
})

afterEach(() => {
  cleanup()
  clients.forEach((client) => client.clear())
  clients.length = 0
  setAccessToken(null)
  vi.restoreAllMocks()
  vi.useRealTimers()
})

const setup = (path: string) => {
  const client = new QueryClient()
  clients.push(client)
  const router = createRouter({
    routeTree,
    context: { auth: undefined! },
    history: createMemoryHistory({ initialEntries: [path] }),
  })
  render(
    <QueryClientProvider client={client}>
      <AuthProvider>
        <AuthRouterProvider router={router} />
      </AuthProvider>
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

it('routes a confirmed business authentication failure through Provider and the guard', async () => {
  setAccessToken('original')
  refresh.mockResolvedValue(tokens())
  const router = setup('/app')
  await screen.findByRole('heading', { name: 'Welcome to FoC' })
  const adapter = vi.fn(async (config) => {
    throw new AxiosError('Unauthorized', undefined, config, undefined, {
      config,
      status: 401,
      statusText: '',
      headers: {},
      data: {},
    })
  })

  await act(async () => {
    await expect(
      axiosClient.get('/users/test', { adapter }),
    ).rejects.toBeInstanceOf(AxiosError)
  })

  expect(
    await screen.findByRole('heading', { name: 'Welcome Back' }),
  ).toBeInTheDocument()
  expect(router.state.location.pathname).toBe('/login')
  expect(getAccessToken()).toBeNull()
  expect(adapter).toHaveBeenCalledTimes(2)
  expect(refresh).toHaveBeenCalledTimes(1)
})

const deferred = <T,>() => {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((done, fail) => {
    resolve = done
    reject = fail
  })
  return { promise, resolve, reject }
}

const unauthorized = (config: InternalAxiosRequestConfig): never => {
  throw new AxiosError('Unauthorized', undefined, config, undefined, {
    config,
    status: 401,
    statusText: '',
    headers: {},
    data: {},
  })
}

const startLogoutDuringRefresh = async () => {
  setAccessToken('original')
  const pendingRefresh = deferred<AccessTokenResponse>()
  const pendingLogout = deferred<void>()
  refresh.mockReturnValue(pendingRefresh.promise)
  vi.mocked(logoutUser).mockReturnValue(pendingLogout.promise)
  const router = setup('/app')
  const button = await screen.findByRole('button', { name: 'Log out' })
  const request = axiosClient.get('/users/test', {
    adapter: async (config) => unauthorized(config),
  })
  const rejectedRequest = expect(request).rejects.toBeInstanceOf(Error)
  await waitFor(() => expect(refresh).toHaveBeenCalledTimes(1))
  fireEvent.click(button)
  expect(logoutUser).not.toHaveBeenCalled()
  expect(getAccessToken()).toBe('original')
  return { pendingRefresh, pendingLogout, rejectedRequest, router }
}

it('saves an in-flight refresh result before logout and pauses new recovery', async () => {
  const { pendingRefresh, pendingLogout, rejectedRequest } =
    await startLogoutDuringRefresh()
  await expect(
    axiosClient.get('/users/another', {
      adapter: async (config) => unauthorized(config),
    }),
  ).rejects.toBeInstanceOf(AxiosError)
  expect(refresh).toHaveBeenCalledTimes(1)

  await act(async () => {
    pendingRefresh.resolve(tokens())
    await rejectedRequest
  })
  await waitFor(() => expect(logoutUser).toHaveBeenCalledTimes(1))
  expect(getAccessToken()).toBe('token')

  await act(async () => pendingLogout.resolve())
  await screen.findByRole('heading', { name: 'Welcome Back' })
  expect(getAccessToken()).toBeNull()
})

it('continues logout after the in-flight refresh request fails', async () => {
  const { pendingRefresh, pendingLogout, rejectedRequest } =
    await startLogoutDuringRefresh()
  await act(async () => {
    pendingRefresh.reject(new AuthRequestError())
    await rejectedRequest
  })
  await waitFor(() => expect(logoutUser).toHaveBeenCalledTimes(1))
  expect(getAccessToken()).toBe('original')

  await act(async () => pendingLogout.resolve())
  await screen.findByRole('heading', { name: 'Welcome Back' })
  expect(getAccessToken()).toBeNull()
})

it('honors an independent refresh 401 even when logout also fails', async () => {
  const { pendingRefresh, pendingLogout, rejectedRequest, router } =
    await startLogoutDuringRefresh()
  await act(async () => {
    pendingRefresh.reject(new AuthRequestError(401))
    await rejectedRequest
  })
  await waitFor(() => expect(logoutUser).toHaveBeenCalledTimes(1))
  expect(getAccessToken()).toBeNull()

  await act(async () => pendingLogout.reject(new Error('offline')))
  await screen.findByRole('heading', { name: 'Welcome Back' })
  expect(router.state.location.pathname).toBe('/login')
})

const startLogoutWithRouteCheck = async () => {
  setAccessToken('token')
  const pendingLogout = deferred<void>()
  vi.mocked(logoutUser).mockReturnValue(pendingLogout.promise)
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
  expect(getAccessToken()).toBe('token')

  let routeCheck!: Promise<void>
  let routeCheckFinished = false
  await act(async () => {
    routeCheck = router.invalidate().then(() => {
      routeCheckFinished = true
    })
  })
  expect(routeCheckFinished).toBe(false)
  expect(router.state.location.pathname).toBe('/app')
  expect(refresh).not.toHaveBeenCalled()
  return { router, pendingLogout, routeCheck }
}

it('waits for successful logout before completing a route check', async () => {
  const { router, pendingLogout, routeCheck } =
    await startLogoutWithRouteCheck()
  await act(async () => {
    pendingLogout.resolve()
    await routeCheck
  })
  await screen.findByRole('heading', { name: 'Welcome Back' })
  expect(getAccessToken()).toBeNull()
  await act(async () => {
    await router.navigate({ to: '/app' })
  })
  expect(router.state.location.pathname).toBe('/login')
  expect(refresh).not.toHaveBeenCalled()
})

it('stays on the app after logout failure, restores recovery and retries with the same button', async () => {
  const { router, pendingLogout, routeCheck } =
    await startLogoutWithRouteCheck()
  await act(async () => {
    pendingLogout.reject(new Error('offline'))
    await routeCheck
  })
  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Unable to log out. Please try again.',
  )
  expect(router.state.location.pathname).toBe('/app')
  expect(getAccessToken()).toBe('token')

  // Recovery is allowed again after the failed logout.
  refresh.mockResolvedValue({ ...tokens(), accessToken: 'renewed' })
  let attempts = 0
  await act(async () => {
    await axiosClient.get('/users/test', {
      adapter: async (config) => {
        if (attempts++ === 0) unauthorized(config)
        return { config, status: 200, statusText: '', headers: {}, data: {} }
      },
    })
  })
  expect(getAccessToken()).toBe('renewed')
  expect(refresh).toHaveBeenCalledTimes(1)

  vi.mocked(logoutUser).mockResolvedValue()
  await userEvent.setup().click(screen.getByRole('button', { name: 'Log out' }))
  await screen.findByRole('heading', { name: 'Welcome Back' })
  expect(router.state.location.pathname).toBe('/login')
  expect(logoutUser).toHaveBeenCalledTimes(2)
  expect(getAccessToken()).toBeNull()
  expect(refresh).toHaveBeenCalledTimes(1)
})
