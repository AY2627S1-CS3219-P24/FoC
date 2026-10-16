import { useMutation, useQueryClient } from '@tanstack/react-query'
import { actOnOrder, createOrder } from '../api/orders.api'
import { errandKeys } from './useOrders'
import type { CreateErrandValues } from '../schemas/createErrand.schema'
import type { OrderAction, OrderUser } from '../types/errand.types'

export const useOrderActions = (user?: OrderUser) => {
  const client = useQueryClient()
  const requireUser = () => {
    if (!user) throw new Error('Please wait for your account to load.')
    return user
  }
  const invalidate = () =>
    client.invalidateQueries({ queryKey: errandKeys.user(user?.id) })
  const create = useMutation({
    mutationFn: (values: CreateErrandValues) =>
      createOrder(requireUser(), values),
    onSuccess: invalidate,
    retry: false,
  })
  const action = useMutation({
    mutationFn: ({
      id,
      action: operation,
    }: {
      id: string
      action: OrderAction
    }) => actOnOrder(requireUser(), id, operation),
    onSettled: invalidate,
    retry: false,
  })
  return { create, action }
}
