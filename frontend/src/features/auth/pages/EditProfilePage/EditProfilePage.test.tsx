import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryHistory, createRouter } from '@tanstack/react-router'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError } from 'axios'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { AuthRouterProvider } from '#/App'
import { routeTree } from '#/routes'
import { AuthProvider } from '../../providers/AuthProvider'
import { getAccessToken, setAccessToken } from '../../lib/accessTokenStore'
import { logoutUser } from '../../api/logoutUser.api'
import * as api from '../../api/userProfile.api'

vi.mock('../../api/userProfile.api')
vi.mock('../../api/logoutUser.api', () => ({ logoutUser: vi.fn() }))

const profile = {
  id: '1',
  name: 'Alex Tan',
  email: 'alex@example.com',
  roles: ['USER'],
  phoneNumber: '+6591235436',
  faculty: 'Computing',
  avatarUrl: null,
}
const clients: QueryClient[] = []

beforeEach(() => {
  vi.resetAllMocks()
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
  setAccessToken('token')
  vi.mocked(api.getUserProfile).mockResolvedValue(profile)
  vi.mocked(api.updateUserProfile).mockImplementation(async (request) => ({
    ...profile,
    ...request,
  }))
  vi.mocked(logoutUser).mockResolvedValue(undefined)
})
afterEach(() => {
  cleanup()
  clients.forEach((client) => client.clear())
  clients.length = 0
  setAccessToken(null)
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

const setup = async () => {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  clients.push(client)
  const router = createRouter({
    routeTree,
    context: { auth: undefined! },
    history: createMemoryHistory({ initialEntries: ['/profile/edit'] }),
  })
  render(
    <QueryClientProvider client={client}>
      <AuthProvider>
        <AuthRouterProvider router={router} />
      </AuthProvider>
    </QueryClientProvider>,
  )
  await screen.findByRole('heading', { name: 'Edit Profile' })
  return { router, client, user: userEvent.setup() }
}

it('loads the profile and saves optional fields with the existing email, updating the header', async () => {
  const { user } = await setup()
  await user.clear(screen.getByLabelText(/Full Name/))
  await user.type(screen.getByLabelText(/Full Name/), 'Jamie Tan')
  await user.clear(screen.getByLabelText('Phone Number'))
  await user.click(screen.getByRole('button', { name: 'Save Changes' }))
  await screen.findByText('Profile saved.')
  expect(api.updateUserProfile).toHaveBeenCalledWith(
    {
      name: 'Jamie Tan',
      email: profile.email,
      phoneNumber: '',
      faculty: 'Computing',
    },
    expect.anything(),
  )
  expect(api.changePassword).not.toHaveBeenCalled()
  expect(
    screen.getByRole('button', { name: 'Open account for Jamie Tan' }),
  ).toBeInTheDocument()
})

it('shows backend field errors without reporting success', async () => {
  vi.mocked(api.updateUserProfile).mockRejectedValue(
    new AxiosError('Bad request', undefined, undefined, undefined, {
      data: {
        message: 'Validation failed',
        fieldErrors: { phoneNumber: 'Include a country code.' },
      },
      status: 400,
      statusText: 'Bad Request',
      headers: {},
      config: {} as never,
    }),
  )
  const { user } = await setup()
  await user.click(screen.getByRole('button', { name: 'Save Changes' }))
  expect(await screen.findByText('Include a country code.')).toBeInTheDocument()
  expect(screen.getByLabelText('Phone Number')).toHaveAttribute(
    'aria-invalid',
    'true',
  )
  expect(screen.queryByText('Profile saved.')).not.toBeInTheDocument()
})

it('does not submit incomplete password changes', async () => {
  const { user } = await setup()
  await user.type(screen.getByLabelText('New password'), 'NewPassword2')
  await user.click(screen.getByRole('button', { name: 'Save Changes' }))
  expect(
    await screen.findByText('Enter your current password.'),
  ).toBeInTheDocument()
  expect(api.updateUserProfile).not.toHaveBeenCalled()
})

it('explains partial success when the current password is incorrect', async () => {
  vi.mocked(api.changePassword).mockRejectedValue(
    new AxiosError('Bad request', undefined, undefined, undefined, {
      data: {
        message: 'Validation failed',
        fieldErrors: { currentPassword: 'Current password is incorrect' },
      },
      status: 400,
      statusText: 'Bad Request',
      headers: {},
      config: {} as never,
    }),
  )
  const { user } = await setup()
  await user.type(screen.getByLabelText('Current password'), 'WrongPassword1')
  await user.type(screen.getByLabelText('New password'), 'NewPassword2')
  await user.click(screen.getByRole('button', { name: 'Save Changes' }))
  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Profile saved, but password was not changed.',
  )
  expect(screen.getByText('Current password is incorrect')).toBeInTheDocument()
  expect(logoutUser).not.toHaveBeenCalled()
})

it('ends the session and clears profile cache after changing the password', async () => {
  const { user, router, client } = await setup()
  await user.type(screen.getByLabelText('Current password'), 'CurrentPassword1')
  await user.type(screen.getByLabelText('New password'), 'NewPassword2')
  await user.click(screen.getByRole('button', { name: 'Save Changes' }))
  await screen.findByRole('heading', { name: 'Welcome Back' })
  expect(router.state.location.pathname).toBe('/login')
  expect(getAccessToken()).toBeNull()
  expect(client.getQueryData(['current-user', 'profile'])).toBeUndefined()
})

it('loads protected avatar bytes, uploads a photo, and removes it', async () => {
  const create = vi.fn().mockReturnValue('blob:avatar')
  const revoke = vi.fn()
  vi.stubGlobal(
    'URL',
    class extends URL {
      static createObjectURL = create
      static revokeObjectURL = revoke
    },
  )
  const blob = new Blob(['photo'], { type: 'image/png' })
  vi.mocked(api.getAvatar).mockResolvedValue(blob)
  vi.mocked(api.uploadAvatar).mockResolvedValue({
    ...profile,
    avatarUrl: '/users/me/avatar?v=new',
  })
  const { user } = await setup()
  const photo = new File(['photo'], 'avatar.png', { type: 'image/png' })
  await user.upload(screen.getByLabelText('Profile photo'), photo)
  await screen.findByText('Photo updated.')
  await waitFor(() =>
    expect(screen.getByAltText('Alex Tan')).toHaveAttribute(
      'src',
      'blob:avatar',
    ),
  )
  expect(api.getAvatar).toHaveBeenCalledWith(expect.any(AbortSignal))
  await user.click(screen.getByRole('button', { name: 'Remove' }))
  await screen.findByText('Photo removed.')
  expect(screen.queryByAltText('Alex Tan')).not.toBeInTheDocument()
})
