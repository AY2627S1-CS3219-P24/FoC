import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, test, vi } from 'vitest'
import { AxiosError, AxiosHeaders } from 'axios'
import { axiosClient } from '#/lib/axiosClient'
import { MyErrandsPage } from './MyErrandsPage'
import type { CreditState, Errand } from '../../types/errand.types'

const errand: Errand = {
  id: '11111111-1111-4111-8111-111111111111',
  requester: { id: '22222222-2222-4222-8222-222222222222', name: 'Alex' },
  courier: { id: '33333333-3333-4333-8333-333333333333', name: 'Jamie' },
  supplierId: '44444444-4444-4444-8444-444444444444',
  pickup: { name: 'Campus Cafe', address: 'COM3, level 1' },
  deliveryLocation: 'COM2 foyer',
  description: 'Collect lunch',
  notes: 'Call when arriving',
  creditReward: 5,
  status: 'ACCEPTED',
  creditState: 'RESERVED',
  creationFailureCode: null,
  createdAt: '2026-10-21T10:00:00Z',
  updatedAt: '2026-10-21T10:05:00Z',
  expiresAt: '2099-10-21T11:00:00Z',
  acceptedAt: '2026-10-21T10:05:00Z',
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
      <MyErrandsPage />
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

test('loads assigned errands, confirms pickup once and refreshes their status', async () => {
  let current = errand
  const get = vi
    .spyOn(axiosClient, 'get')
    .mockImplementation(async (url) =>
      url === '/orders/mine' ? list([current]) : { data: current },
    )
  let finish!: () => void
  const pending = new Promise<void>((resolve) => {
    finish = resolve
  })
  const post = vi.spyOn(axiosClient, 'post').mockImplementation(async () => {
    await pending
    current = { ...errand, status: 'PICKED_UP' }
    return {
      data: {
        errandId: errand.id,
        status: 'PICKED_UP',
        creditState: 'RESERVED',
      },
    }
  })
  const user = setup()
  await openDetails(user)
  expect(get).toHaveBeenCalledWith('/orders/mine', {
    params: { as: 'courier', page: 0, size: 20 },
    signal: expect.any(AbortSignal),
  })
  expect(get).toHaveBeenCalledWith(`/orders/${errand.id}`, {
    signal: expect.any(AbortSignal),
  })
  await user.click(screen.getByRole('button', { name: 'Confirm pickup' }))
  expect(screen.getByRole('button', { name: 'Confirming...' })).toBeDisabled()
  expect(screen.getByRole('button', { name: 'Close' })).toBeDisabled()
  await act(async () => finish())
  await screen.findByText('Pickup confirmed.')
  await waitFor(() =>
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument(),
  )
  expect(screen.getByText('Picked up')).toBeInTheDocument()
  expect(post).toHaveBeenCalledExactlyOnceWith(`/orders/${errand.id}/pick-up`)
})

test.each([
  ['SETTLEMENT_PENDING', 'Payment pending'],
  ['SETTLED', 'Paid'],
  [null, 'Unavailable'],
] as const)(
  'shows the fresh payment state %s without offering pickup',
  async (creditState, label) => {
    vi.spyOn(axiosClient, 'get').mockImplementation(async (url) =>
      url === '/orders/mine'
        ? list([
            {
              ...errand,
              status: 'COMPLETED',
              creditState: 'SETTLEMENT_PENDING',
            },
          ])
        : {
            data: {
              ...errand,
              status: 'COMPLETED',
              creditState: creditState as CreditState | null,
            },
          },
    )
    const user = setup()
    await openDetails(user)
    expect(screen.getByText(`Payment: ${label}`)).toBeInTheDocument()
    expect(screen.getByText('Status: Completed')).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: 'Confirm pickup' }),
    ).not.toBeInTheDocument()
  },
)

test('requests the next page of courier assignments', async () => {
  const get = vi
    .spyOn(axiosClient, 'get')
    .mockResolvedValue(list([errand], 0, 21))
  const user = setup()
  await screen.findByText('Collect lunch')
  get.mockResolvedValue(
    list([{ ...errand, description: 'Second assignment page' }], 1, 21),
  )
  await user.click(screen.getByRole('button', { name: 'Next' }))
  await screen.findByText('Second assignment page')
  expect(get).toHaveBeenLastCalledWith('/orders/mine', {
    params: { as: 'courier', page: 1, size: 20 },
    signal: expect.any(AbortSignal),
  })
  expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()
  expect(screen.getByRole('button', { name: 'Previous' })).toBeEnabled()
})

test('reloads state after a failed pickup response without submitting again', async () => {
  let current = errand
  vi.spyOn(axiosClient, 'get').mockImplementation(async (url) =>
    url === '/orders/mine' ? list([current]) : { data: current },
  )
  const failure = new AxiosError('Conflict')
  failure.response = {
    status: 409,
    statusText: 'Conflict',
    headers: {},
    config: { headers: new AxiosHeaders() },
    data: { message: 'Order must be accepted before pickup' },
  }
  const post = vi.spyOn(axiosClient, 'post').mockImplementation(async () => {
    current = { ...errand, status: 'PICKED_UP' }
    throw failure
  })
  const user = setup()
  await openDetails(user)
  await user.click(screen.getByRole('button', { name: 'Confirm pickup' }))
  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Order must be accepted before pickup',
  )
  await screen.findByText('Status: Picked up')
  expect(
    screen.queryByRole('button', { name: 'Confirm pickup' }),
  ).not.toBeInTheDocument()
  expect(screen.queryByText('Pickup confirmed.')).not.toBeInTheDocument()
  expect(post).toHaveBeenCalledTimes(1)
})

test('allows retrying the assigned list and shows its empty state', async () => {
  vi.spyOn(axiosClient, 'get')
    .mockRejectedValueOnce(new Error('Offline'))
    .mockResolvedValue(list([]))
  const user = setup()
  await screen.findByRole('alert')
  await user.click(screen.getByRole('button', { name: 'Try again' }))
  await screen.findByText('No assigned errands on this page.')
})

test('does not offer pickup when the fresh details request fails', async () => {
  vi.spyOn(axiosClient, 'get').mockImplementation(async (url) => {
    if (url === '/orders/mine') return list()
    throw new Error('Unavailable')
  })
  const user = setup()
  await user.click(
    await screen.findByRole('button', {
      name: 'View errand from Campus Cafe to COM2 foyer',
    }),
  )
  await screen.findByRole('alert')
  expect(
    screen.queryByRole('button', { name: 'Confirm pickup' }),
  ).not.toBeInTheDocument()
})
