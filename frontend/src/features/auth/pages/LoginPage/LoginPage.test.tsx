import {
  QueryClient,
  QueryClientProvider,
  onlineManager,
} from '@tanstack/react-query'
import {
  RouterProvider,
  createMemoryHistory,
  createRouter,
} from '@tanstack/react-router'
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError, AxiosHeaders } from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { loginUser } from '#/features/auth/api/loginUser.api'
import { routeTree } from '#/routes'
import { clearSession, getSession } from '#/features/auth/lib/authSession'
import type { AccessTokenResponse } from '#/features/auth/types/auth.types'

vi.mock('#/features/auth/api/loginUser.api', () => ({ loginUser: vi.fn() }))

const loginMock = vi.mocked(loginUser)
const tokens: AccessTokenResponse = {
  accessToken: 'test-token',
  expiresAt: new Date(Date.now() + 300_000).toISOString(),
}

const clients: Array<QueryClient> = []
let previousOnline = true
const httpError = (status: number) =>
  new AxiosError('Internal details', undefined, undefined, undefined, {
    status,
    statusText: 'Error',
    data: { message: 'Internal details' },
    headers: {},
    config: { headers: new AxiosHeaders() },
  })

beforeEach(() => {
  vi.spyOn(window, 'scrollTo').mockImplementation(vi.fn())
  clearSession(true)
  loginMock.mockReset()
  previousOnline = onlineManager.isOnline()
  onlineManager.setOnline(true)
})

afterEach(() => {
  cleanup()
  clearSession(true)
  clients.forEach((client) => client.clear())
  clients.length = 0
  onlineManager.setOnline(previousOnline)
  vi.restoreAllMocks()
})

const setup = async (path = '/login') => {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
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
  await screen.findByRole('heading', { name: 'Welcome Back' })
  return router
}

const fill = async () => {
  const user = userEvent.setup()
  await user.type(screen.getByLabelText('Email'), ' JAMIE@EXAMPLE.COM ')
  await user.type(screen.getByLabelText('Password'), ' password123 ')
  return user
}

describe('LoginPage', () => {
  it('blocks rapid, pending and successful resubmissions and replaces the registration notice', async () => {
    let resolveRequest!: (value: AccessTokenResponse) => void
    loginMock.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveRequest = resolve
        }),
    )
    const router = await setup('/login?registered=true')

    expect(screen.getByRole('status')).toHaveTextContent(
      'Account created successfully. Please log in.',
    )
    await fill()
    const form = screen.getByRole('button', { name: 'Log In' }).closest('form')!
    act(() => {
      form.dispatchEvent(
        new Event('submit', { bubbles: true, cancelable: true }),
      )
      form.dispatchEvent(
        new Event('submit', { bubbles: true, cancelable: true }),
      )
    })
    await waitFor(() => expect(loginMock).toHaveBeenCalledTimes(1))

    expect(
      await screen.findByRole('button', { name: 'Logging in…' }),
    ).toBeDisabled()
    expect(screen.getByLabelText('Email')).toBeDisabled()
    expect(screen.getByLabelText('Password')).toBeDisabled()
    fireEvent.submit(form)

    expect(loginMock).toHaveBeenCalledExactlyOnceWith({
      email: 'jamie@example.com',
      password: ' password123 ',
    })
    await act(async () => resolveRequest(tokens))

    expect(
      await screen.findByRole('heading', { name: 'Welcome to FoC' }),
    ).toBeInTheDocument()
    expect(getSession()).toEqual(tokens)
    expect(
      screen.queryByText('Account created successfully. Please log in.'),
    ).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: 'Log In' }),
    ).not.toBeInTheDocument()
    expect(loginMock).toHaveBeenCalledTimes(1)
    expect(router.state.location.pathname).toBe('/app')
    expect(screen.queryByText(tokens.accessToken)).not.toBeInTheDocument()
  })

  it('releases the lock after failure and allows corrected input to succeed', async () => {
    loginMock
      .mockRejectedValueOnce(httpError(401))
      .mockResolvedValueOnce(tokens)
    await setup()
    const user = await fill()
    await user.click(screen.getByRole('button', { name: 'Log In' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Incorrect email or password.',
    )
    expect(screen.getByLabelText('Password')).toHaveValue(' password123 ')
    expect(screen.getByRole('button', { name: 'Log In' })).toBeEnabled()
    await user.clear(screen.getByLabelText('Password'))
    await user.type(screen.getByLabelText('Password'), 'correct-password')
    await user.click(screen.getByRole('button', { name: 'Log In' }))

    expect(
      await screen.findByRole('heading', { name: 'Welcome to FoC' }),
    ).toBeInTheDocument()
    expect(getSession()).toEqual(tokens)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(loginMock).toHaveBeenCalledTimes(2)
    expect(loginMock).toHaveBeenLastCalledWith({
      email: 'jamie@example.com',
      password: 'correct-password',
    })
  })

  it.each([
    [400, 'Please check your login details and try again.'],
    [502, 'Unable to log in. Please try again.'],
  ])(
    'handles HTTP %s without exposing server details',
    async (status, message) => {
      loginMock.mockRejectedValue(httpError(status))
      const router = await setup()
      const user = await fill()
      await user.click(screen.getByRole('button', { name: 'Log In' }))

      expect(await screen.findByRole('alert')).toHaveTextContent(message)
      expect(screen.queryByText('Internal details')).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Log In' })).toBeEnabled()
      expect(router.state.location.pathname).toBe('/login')
    },
  )

  it.each([
    [
      new AxiosError('Network Error', 'ERR_NETWORK'),
      'Unable to reach the server. Please try again.',
    ],
    [new Error('Internal details'), 'Unable to log in. Please try again.'],
  ])('handles a failure without a response: %s', async (error, message) => {
    loginMock.mockRejectedValue(error)
    await setup()
    const user = await fill()
    await user.click(screen.getByRole('button', { name: 'Log In' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(message)
    expect(screen.getByRole('button', { name: 'Log In' })).toBeEnabled()
    expect(loginMock).toHaveBeenCalledTimes(1)
  })

  it('attempts the request while marked offline', async () => {
    loginMock.mockRejectedValue(new AxiosError('Network Error', 'ERR_NETWORK'))
    await setup()
    const user = await fill()
    act(() => onlineManager.setOnline(false))
    await user.click(screen.getByRole('button', { name: 'Log In' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Unable to reach the server.',
    )
    expect(loginMock).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('button', { name: 'Log In' })).toBeEnabled()
  })
})
