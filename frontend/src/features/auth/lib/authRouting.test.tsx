import { AuthRouterProvider } from '#/App'
import { AuthProvider } from '#/features/auth/providers/AuthProvider'
import { AxiosError } from 'axios'
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

vi.mock('../api/userProfile.api', () => ({
  updateUserProfile: vi.fn(),
  changePassword: vi.fn(),
  uploadAvatar: vi.fn(),
  removeAvatar: vi.fn(),
  getUserProfile: vi.fn(async () => ({
    id: '1',
    name: 'Alex Tan',
    email: 'alex@example.com',
    roles: ['USER'],
    avatarUrl: null,
  })),
}))

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

it('switches between requestor and courier modes through the menu', async () => {
  setAccessToken('token')
  const router = setup('/app')
  const user = userEvent.setup()
  await screen.findByRole('heading', { name: 'What do you need?' })

  await user.click(screen.getByRole('button', { name: 'Request' }))
  await user.click(await screen.findByRole('menuitem', { name: 'Deliver' }))
  await screen.findByRole('heading', { name: 'Find an errand' })
  expect(router.state.location.pathname).toBe('/courier')
  expect(
    screen.queryByRole('heading', { name: 'What do you need?' }),
  ).not.toBeInTheDocument()

  await user.click(screen.getByRole('button', { name: 'Deliver' }))
  await user.click(await screen.findByRole('menuitem', { name: 'Request' }))
  await screen.findByRole('heading', { name: 'What do you need?' })
  expect(router.state.location.pathname).toBe('/app')
  expect(
    screen.queryByRole('heading', { name: 'Find an errand' }),
  ).not.toBeInTheDocument()
})

it('shares one account layout across home, courier and profile routes', async () => {
  setAccessToken('token')
  const router = setup('/app')
  const user = userEvent.setup()
  await screen.findByRole('heading', { name: 'What do you need?' })
  await screen.findByRole('button', { name: 'Open account for Alex Tan' })
  const header = screen.getByRole('banner')

  await act(async () => {
    await router.navigate({ to: '/courier' })
  })
  await screen.findByRole('heading', { name: 'Find an errand' })
  expect(screen.getByRole('banner')).toBe(header)
  expect(screen.getAllByRole('main')).toHaveLength(1)

  await user.click(
    screen.getByRole('button', { name: 'Open account for Alex Tan' }),
  )
  expect(router.state.location.pathname).toBe('/courier')
  await user.click(await screen.findByRole('menuitem', { name: 'Profile' }))
  await screen.findByRole('heading', { name: 'My Profile' })
  expect(router.state.location.pathname).toBe('/profile')
  expect(screen.getByRole('banner')).toBe(header)

  await user.click(screen.getByRole('link', { name: 'Edit Profile' }))
  await screen.findByRole('heading', { name: 'Edit Profile' })
  expect(router.state.location.pathname).toBe('/profile/edit')
  expect(screen.getByRole('banner')).toBe(header)
  expect(screen.getAllByRole('main')).toHaveLength(1)
  vi.mocked(logoutUser).mockResolvedValue()
  await user.click(
    screen.getByRole('button', { name: 'Open account for Alex Tan' }),
  )
  await user.click(await screen.findByRole('menuitem', { name: 'Log out' }))
  await screen.findByRole('heading', { name: 'Welcome Back' })
  expect(router.state.location.pathname).toBe('/login')
  expect(getAccessToken()).toBeNull()
  expect(logoutUser).toHaveBeenCalledOnce()
})

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
      screen.queryByRole('heading', { name: 'What do you need?' }),
    ).not.toBeInTheDocument()

    await act(async () => vi.advanceTimersByTimeAsync(1))
    expect(screen.getByRole('status')).toHaveTextContent('Loading…')
    expect(
      screen.queryByRole('heading', { name: 'What do you need?' }),
    ).not.toBeInTheDocument()

    await act(async () => resolve(tokens()))

    expect(
      screen.getByRole('heading', { name: 'What do you need?' }),
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
    screen.getByRole('heading', { name: 'What do you need?' }),
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
    screen.queryByRole('heading', { name: 'What do you need?' }),
  ).not.toBeInTheDocument()
  expect(router.state.location.pathname).toBe('/app')

  await userEvent
    .setup()
    .click(screen.getByRole('button', { name: 'Try again' }))

  expect(
    await screen.findByRole('heading', { name: 'What do you need?' }),
  ).toBeInTheDocument()
  expect(refresh).toHaveBeenCalledTimes(2)
})

it('routes a confirmed business authentication failure through Provider and the guard', async () => {
  setAccessToken('original')
  refresh.mockResolvedValue(tokens())
  const router = setup('/app')
  await screen.findByRole('heading', { name: 'What do you need?' })
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

it.each(['success', 'timeout'])(
  'waits for the current refresh %s before logout without restoring the token',
  async (outcome) => {
    setAccessToken('original')
    let finishRefresh!: () => void
    refresh.mockReturnValue(
      new Promise((resolve, reject) => {
        finishRefresh = () =>
          outcome === 'success'
            ? resolve(tokens())
            : reject(new AuthRequestError())
      }),
    )
    vi.mocked(logoutUser).mockResolvedValue()
    const router = setup('/app')
    const button = await screen.findByRole('button', { name: 'Log out' })
    const adapter = vi.fn(async (config) => {
      throw new AxiosError('Unauthorized', undefined, config, undefined, {
        config,
        status: 401,
        statusText: '',
        headers: {},
        data: {},
      })
    })
    const request = axiosClient.get('/users/test', { adapter })
    const rejectedRequest = expect(request).rejects.toBeInstanceOf(AxiosError)
    await waitFor(() => expect(refresh).toHaveBeenCalledTimes(1))

    fireEvent.click(button)
    fireEvent.click(button)
    expect(getAccessToken()).toBeNull()
    expect(logoutUser).not.toHaveBeenCalled()
    expect(button).toBeEnabled()
    expect(router.state.location.pathname).toBe('/app')

    // A further 401 during logout must not start another refresh.
    await expect(
      axiosClient.get('/users/another', { adapter }),
    ).rejects.toBeInstanceOf(AxiosError)
    expect(refresh).toHaveBeenCalledTimes(1)
    await act(async () => {
      finishRefresh()
      await rejectedRequest
    })
    expect(
      await screen.findByRole('heading', { name: 'Welcome Back' }),
    ).toBeInTheDocument()
    expect(logoutUser).toHaveBeenCalledTimes(1)
    expect(getAccessToken()).toBeNull()
    expect(adapter).toHaveBeenCalledTimes(2)
  },
)

it.each(['success', 'failure'])(
  'keeps the app unchanged until logout %s, then opens login',
  async (outcome) => {
    setAccessToken(tokens().accessToken)
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
      screen.getByRole('heading', { name: 'What do you need?' }),
    ).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/app')

    await act(async () => finish())
    expect(
      await screen.findByRole('heading', { name: 'Welcome Back' }),
    ).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/login')
    await act(async () => {
      await router.navigate({ to: '/app' })
    })
    expect(router.state.location.pathname).toBe('/login')
    expect(getAccessToken()).toBeNull()
    expect(refresh).not.toHaveBeenCalled()
  },
)
