import { act, cleanup, renderHook } from '@testing-library/react'
import {
  QueryClient,
  QueryClientProvider,
  focusManager,
} from '@tanstack/react-query'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'
import { useMyErrands, useOrder } from './useOrders'
import { useCreditBalance } from '#/features/credits/hooks/useCreditBalance'
import { getCreditBalance } from '#/features/credits/api/credits.api'
import { getOrder, listMyOrders } from '../api/orders.api'
import { orderFixture } from '../test/orderFixtures'
import { replaceSession } from '#/features/auth/lib/accessTokenStore'
import { useSession } from '#/features/auth/hooks/useSession'
import { getUserProfile } from '#/features/auth/api/userProfile.api'

const token = (sub: string) =>
  `header.${btoa(JSON.stringify({ sub, roles: ['USER'] }))}.signature`
vi.mock('#/features/auth/api/userProfile.api', () => ({
  getUserProfile: vi.fn(),
}))
vi.mock('../api/orders.api', () => ({
  getOrder: vi.fn(),
  listMyOrders: vi.fn(),
  listAvailableOrders: vi.fn(),
  listRequesterOrders: vi.fn(),
}))
vi.mock('#/features/credits/api/credits.api', () => ({
  getCreditBalance: vi.fn(),
}))
const clients: Array<QueryClient> = []
const wrapper = () => {
  const client = new QueryClient({
    defaultOptions: { queries: { staleTime: 60_000, retry: false } },
  })
  clients.push(client)
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
}
const tick = async (ms = 1) => {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms)
  })
}
beforeEach(() => {
  vi.useFakeTimers()
  vi.clearAllMocks()
  focusManager.setFocused(true)
  replaceSession(token('requester'))
  vi.mocked(getUserProfile).mockRejectedValue(new Error('Profile unavailable'))
  vi.mocked(listMyOrders).mockResolvedValue({
    items: [],
    total: 0,
    page: 0,
    size: 20,
  })
  vi.mocked(getCreditBalance).mockResolvedValue({
    availableBalance: 15,
    reservedBalance: 0,
  })
})
afterEach(() => {
  cleanup()
  clients.forEach((client) => client.clear())
  clients.length = 0
  replaceSession(null)
  focusManager.setFocused(undefined)
  vi.useRealTimers()
})
it('polls lists only in the foreground and immediately refreshes on focus despite global cache freshness', async () => {
  renderHook(
    () => useMyErrands({ as: 'all', view: 'active', page: 0, size: 20 }),
    { wrapper: wrapper() },
  )
  await tick()
  expect(listMyOrders).toHaveBeenCalledTimes(1)
  expect(getUserProfile).not.toHaveBeenCalled()
  await tick(15_000)
  expect(listMyOrders).toHaveBeenCalledTimes(2)
  act(() => focusManager.setFocused(false))
  await tick(30_000)
  expect(listMyOrders).toHaveBeenCalledTimes(2)
  act(() => focusManager.setFocused(true))
  await tick()
  expect(listMyOrders).toHaveBeenCalledTimes(3)
})
it('uses five-second detail polling until funds settle then stops and refreshes the balance', async () => {
  const pending = orderFixture({
    status: 'COMPLETED',
    creditState: 'SETTLEMENT_PENDING',
  })
  vi.mocked(getOrder)
    .mockResolvedValueOnce(pending)
    .mockResolvedValue({ ...pending, creditState: 'SETTLED' })
  renderHook(
    () => {
      useCreditBalance(useSession().userId)
      return useOrder(pending.id)
    },
    { wrapper: wrapper() },
  )
  await tick()
  expect(getOrder).toHaveBeenCalledTimes(1)
  await tick(5_000)
  expect(getOrder).toHaveBeenCalledTimes(2)
  expect(getCreditBalance).toHaveBeenCalledTimes(2)
  await tick(15_000)
  expect(getOrder).toHaveBeenCalledTimes(2)
})
it('scopes cached orders and balances by the signed-in user', async () => {
  const wrap = wrapper()
  const query = renderHook(
    () => ({
      orders: useMyErrands({ as: 'all', view: 'active', page: 0, size: 20 }),
      balance: useCreditBalance(useSession().userId),
    }),
    { wrapper: wrap },
  )
  await tick()
  vi.mocked(getCreditBalance).mockResolvedValue({
    availableBalance: 0,
    reservedBalance: 0,
  })
  act(() => replaceSession(token('another-user')))
  await tick()
  expect(listMyOrders).toHaveBeenCalledTimes(2)
  expect(getCreditBalance).toHaveBeenCalledTimes(2)
  expect(query.result.current.balance.data?.availableBalance).toBe(0)
  expect(clients[0].getQueryData(['credits', 'requester'])).toEqual({
    availableBalance: 15,
    reservedBalance: 0,
  })
})
it('immediately refreshes a settled detail on remount without restarting its interval', async () => {
  vi.mocked(getOrder).mockResolvedValue(
    orderFixture({ status: 'CANCELLED', creditState: 'RELEASED' }),
  )
  const wrap = wrapper()
  const initial = renderHook(() => useOrder(orderFixture().id), {
    wrapper: wrap,
  })
  await tick()
  initial.unmount()
  renderHook(() => useOrder(orderFixture().id), { wrapper: wrap })
  await tick()
  expect(getOrder).toHaveBeenCalledTimes(2)
  await tick(10_000)
  expect(getOrder).toHaveBeenCalledTimes(2)
})
