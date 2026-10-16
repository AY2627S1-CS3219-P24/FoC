import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useUserProfile } from '#/features/auth/hooks/useUserProfile'
import { getOrder, listOrders } from '../api/orders.api'

export const errandKeys = {
  all: ['errands'] as const,
  user: (id?: string) => ['errands', id] as const,
}
export const useOrders = () => {
  const profile = useUserProfile()
  const user = profile.data
  const orders = useQuery({
    queryKey: [...errandKeys.user(user?.id), 'list'],
    queryFn: () => listOrders(user!),
    enabled: !!user,
    retry: false,
  })
  return {
    ...orders,
    user,
    identityError: profile.error,
    refetch: () => (user ? orders.refetch() : profile.refetch()),
  }
}
export const useOrder = (id: string) => {
  const profile = useUserProfile()
  const user = profile.data
  const order = useQuery({
    queryKey: [...errandKeys.user(user?.id), 'detail', id],
    queryFn: () => getOrder(user!, id),
    enabled: !!user,
    retry: false,
  })
  return {
    ...order,
    user,
    identityError: profile.error,
    refetch: () => (user ? order.refetch() : profile.refetch()),
  }
}
export const useOrderClock = () => {
  const [now, setNow] = useState(Date.now)
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [])
  return now
}
