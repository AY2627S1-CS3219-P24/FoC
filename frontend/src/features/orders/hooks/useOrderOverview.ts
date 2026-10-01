import { useQuery } from '@tanstack/react-query'
import { sampleLiveOrders, sampleOrderStats } from '../mocks/adminOverview.mock'

export const orderKeys = {
  all: ['orders'] as const,
  live: () => [...orderKeys.all, 'live'] as const,
  stats: () => [...orderKeys.all, 'stats'] as const,
}

// TEMPORARY: Order Service has no endpoints for these yet, so the hooks resolve
// sample data. Swap each queryFn for an API call once it does; callers stay the same.

export const useLiveOrders = () =>
  useQuery({
    queryKey: orderKeys.live(),
    queryFn: async () => sampleLiveOrders,
  })

export const useOrderStats = () =>
  useQuery({
    queryKey: orderKeys.stats(),
    queryFn: async () => sampleOrderStats,
  })
