import { StrictMode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryHistory, createRouter } from '@tanstack/react-router'
import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { AuthRouterProvider } from '#/App'
import { routeTree } from '#/routes'
import { AuthProvider } from '../../providers/AuthProvider'
import {
  resendVerification,
  verifyEmail,
} from '../../api/emailVerification.api'
import { AuthRequestError } from '../../lib/authRequest'

vi.mock('../../api/emailVerification.api', () => ({
  verifyEmail: vi.fn(),
  resendVerification: vi.fn(),
}))

const clients: Array<QueryClient> = []

beforeEach(() => {
  vi.resetAllMocks()
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
})

afterEach(() => {
  cleanup()
  clients.forEach((client) => client.clear())
  clients.length = 0
  vi.restoreAllMocks()
})

const setup = (path: string) => {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  clients.push(client)
  const router = createRouter({
    routeTree,
    context: { auth: undefined! },
    history: createMemoryHistory({ initialEntries: [path] }),
  })
  render(
    <StrictMode>
      <QueryClientProvider client={client}>
        <AuthProvider>
          <AuthRouterProvider router={router} />
        </AuthProvider>
      </QueryClientProvider>
    </StrictMode>,
  )
  return router
}

it('automatically confirms a link once in StrictMode and then offers login', async () => {
  let complete!: () => void
  vi.mocked(verifyEmail).mockImplementation(
    () =>
      new Promise<void>((resolve) => {
        complete = resolve
      }),
  )
  setup('/verify-email?token=mail-token')
  expect(
    await screen.findByRole('heading', { name: 'Verifying your email…' }),
  ).toBeInTheDocument()
  await waitFor(() =>
    expect(verifyEmail).toHaveBeenCalledExactlyOnceWith(
      'mail-token',
      expect.any(Object),
    ),
  )
  expect(screen.queryByRole('button')).not.toBeInTheDocument()
  await act(async () => complete())
  expect(
    await screen.findByRole('heading', { name: 'Email verified' }),
  ).toBeInTheDocument()
  expect(screen.getByRole('link', { name: 'Log in' })).toHaveAttribute(
    'href',
    '/login',
  )
  expect(verifyEmail).toHaveBeenCalledTimes(1)
})

it('lets an unavailable link request a replacement on the same page', async () => {
  vi.mocked(verifyEmail).mockRejectedValue(new AuthRequestError(400))
  vi.mocked(resendVerification).mockResolvedValue(undefined)
  const router = setup('/verify-email?token=expired-token')
  expect(
    await screen.findByRole('heading', { name: 'Link unavailable' }),
  ).toBeInTheDocument()
  const user = userEvent.setup()
  await user.type(screen.getByLabelText('Email'), ' JAMIE@U.NUS.EDU ')
  await user.click(
    screen.getByRole('button', { name: 'Resend verification email' }),
  )
  expect(
    await screen.findByRole('heading', { name: 'Check your email' }),
  ).toBeInTheDocument()
  expect(resendVerification).toHaveBeenCalledExactlyOnceWith(
    'jamie@u.nus.edu',
    expect.any(Object),
  )
  expect(screen.getByText('jamie@u.nus.edu')).toBeInTheDocument()
  expect(screen.getByRole('status')).toHaveTextContent('inbox and spam')
  expect(router.state.location.pathname).toBe('/verify-email')
})

it('shows the resend interval inline and lets the user retry', async () => {
  vi.mocked(resendVerification)
    .mockRejectedValueOnce(new AuthRequestError(429))
    .mockResolvedValueOnce(undefined)
  setup('/verify-email?email=jamie@u.nus.edu')
  const user = userEvent.setup()
  await user.click(
    await screen.findByRole('button', { name: 'Resend verification email' }),
  )
  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Please wait a minute',
  )
  await user.click(
    screen.getByRole('button', { name: 'Resend verification email' }),
  )
  expect(await screen.findByRole('status')).toHaveTextContent('inbox and spam')
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  expect(verifyEmail).not.toHaveBeenCalled()
})

it('retries verification after a temporary request failure', async () => {
  vi.mocked(verifyEmail)
    .mockRejectedValueOnce(new AuthRequestError())
    .mockResolvedValueOnce(undefined)
  setup('/verify-email?token=mail-token')
  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name: 'Try again' }))
  expect(
    await screen.findByRole('heading', { name: 'Email verified' }),
  ).toBeInTheDocument()
  expect(verifyEmail).toHaveBeenCalledTimes(2)
})
