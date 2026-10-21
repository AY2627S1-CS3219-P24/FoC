import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, test, vi } from 'vitest'
import { AxiosError, AxiosHeaders } from 'axios'
import { axiosClient } from '#/lib/axiosClient'
import { CourierHomePage } from './CourierHomePage'
import type { Errand } from '../../types/errand.types'

vi.mock('#/features/orders/components/RoleSwitcher/RoleSwitcher', () => ({
  RoleSwitcher: () => null,
}))

const errand: Errand = {
  id: '11111111-1111-4111-8111-111111111111',
  requester: { id: '22222222-2222-4222-8222-222222222222', name: 'Alex' },
  courier: null,
  supplierId: '33333333-3333-4333-8333-333333333333',
  pickup: { name: 'Campus Cafe', address: 'COM3, level 1' },
  deliveryLocation: 'COM2 foyer',
  description: 'Collect lunch',
  notes: 'Call when arriving',
  creditReward: 5,
  status: 'OPEN',
  creditState: 'RESERVED',
  creationFailureCode: null,
  createdAt: '2026-10-21T10:00:00Z',
  updatedAt: '2026-10-21T10:00:00Z',
  expiresAt: '2099-10-21T11:00:00Z',
  acceptedAt: null,
  pickedUpAt: null,
  endedAt: null,
}

const clients: QueryClient[] = []
afterEach(() => {
  cleanup()
  clients.forEach((client) => client.clear())
  clients.length = 0
  vi.restoreAllMocks()
})

const setup = () => {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  clients.push(client)
  render(
    <QueryClientProvider client={client}>
      <CourierHomePage />
    </QueryClientProvider>,
  )
  return userEvent.setup()
}

const list = (items = [errand], page = 0, total = items.length) => ({
  data: { items, page, total, size: 20 },
})

const openDetails = async (user: ReturnType<typeof userEvent.setup>) => {
  await user.click(
    await screen.findByRole('button', {
      name: 'View errand from Campus Cafe to COM2 foyer',
    }),
  )
  await screen.findByText('Pickup: COM3, level 1')
}

test('loads real cards and details, accepts once, and refreshes available errands', async () => {
  let accepted = false
  const get = vi.spyOn(axiosClient, 'get').mockImplementation(async (url) => {
    if (url === '/orders') return list(accepted ? [] : [errand])
    return { data: { ...errand, status: accepted ? 'ACCEPTED' : 'OPEN' } }
  })
  const post = vi.spyOn(axiosClient, 'post').mockImplementation(async () => {
    accepted = true
    return {
      data: {
        errandId: errand.id,
        status: 'ACCEPTED',
        creditState: 'RESERVED',
      },
    }
  })
  const user = setup()
  expect(screen.getByRole('searchbox')).toBeDisabled()
  await openDetails(user)
  expect(get).toHaveBeenCalledWith('/orders', {
    params: { page: 0, size: 20 },
    signal: expect.any(AbortSignal),
  })
  expect(get).toHaveBeenCalledWith(`/orders/${errand.id}`, {
    signal: expect.any(AbortSignal),
  })
  await user.click(screen.getByRole('button', { name: 'Accept errand' }))
  await screen.findByText('Errand accepted.')
  expect(post).toHaveBeenCalledExactlyOnceWith(`/orders/${errand.id}/accept`)
  await waitFor(() =>
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument(),
  )
  await screen.findByText('No available errands on this page.')
})

test('requests the next page from the backend', async () => {
  const get = vi
    .spyOn(axiosClient, 'get')
    .mockResolvedValue(list([errand], 0, 21))
  const user = setup()
  await screen.findByText('Collect lunch')
  get.mockResolvedValue(
    list([{ ...errand, description: 'Second page errand' }], 1, 21),
  )
  await user.click(screen.getByRole('button', { name: 'Next' }))
  await screen.findByText('Second page errand')
  expect(get).toHaveBeenLastCalledWith('/orders', {
    params: { page: 1, size: 20 },
    signal: expect.any(AbortSignal),
  })
  expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()
  expect(screen.getByRole('button', { name: 'Previous' })).toBeEnabled()
})

test('allows retrying a failed list request', async () => {
  const get = vi
    .spyOn(axiosClient, 'get')
    .mockRejectedValueOnce(new Error('Offline'))
  get.mockResolvedValue(list())
  const user = setup()
  await screen.findByRole('alert')
  await user.click(screen.getByRole('button', { name: 'Try again' }))
  await screen.findByText('Collect lunch')
})

test.each([
  { status: 'ACCEPTED' as const },
  { expiresAt: '2020-01-01T00:00:00Z' },
])(
  'prevents acceptance when fresh details are unavailable: %j',
  async (changes) => {
    vi.spyOn(axiosClient, 'get').mockImplementation(async (url) =>
      url === '/orders' ? list() : { data: { ...errand, ...changes } },
    )
    const user = setup()
    await openDetails(user)
    expect(screen.getByRole('button', { name: 'Accept errand' })).toBeDisabled()
  },
)

test('shows the backend account-pending message without reporting success', async () => {
  vi.spyOn(axiosClient, 'get').mockImplementation(async (url) =>
    url === '/orders' ? list() : { data: errand },
  )
  const failure = new AxiosError('Conflict')
  failure.response = {
    status: 409,
    statusText: 'Conflict',
    headers: {},
    config: { headers: new AxiosHeaders() },
    data: {
      code: 'CREDIT_ACCOUNT_PENDING',
      message:
        'Your credit account is being prepared. Please try again shortly.',
    },
  }
  vi.spyOn(axiosClient, 'post').mockRejectedValue(failure)
  const user = setup()
  await openDetails(user)
  await user.click(screen.getByRole('button', { name: 'Accept errand' }))
  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Your credit account is being prepared.',
  )
  expect(screen.queryByText('Errand accepted.')).not.toBeInTheDocument()
})

test('reloads details after an uncertain acceptance without automatically posting again', async () => {
  let accepted = false
  vi.spyOn(axiosClient, 'get').mockImplementation(async (url) => {
    if (url === '/orders') return list(accepted ? [] : [errand])
    return { data: { ...errand, status: accepted ? 'ACCEPTED' : 'OPEN' } }
  })
  const post = vi.spyOn(axiosClient, 'post').mockImplementation(async () => {
    accepted = true
    throw new Error('Response lost')
  })
  const user = setup()
  await openDetails(user)
  await user.click(screen.getByRole('button', { name: 'Accept errand' }))
  await screen.findByRole('alert')
  await waitFor(() =>
    expect(
      screen.getByRole('button', { name: 'Accept errand' }),
    ).toBeDisabled(),
  )
  expect(post).toHaveBeenCalledTimes(1)
})
