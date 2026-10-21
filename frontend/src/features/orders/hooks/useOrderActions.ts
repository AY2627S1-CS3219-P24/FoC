import { useMutation, useQueryClient } from '@tanstack/react-query'
import { CanceledError } from 'axios'
import { actOnOrder, createOrder, retryCreation } from '../api/orders.api'
import { errandQueryKeys } from '../constants/errandQueryKeys'
import { useSession } from '#/features/auth/hooks/useSession'
import { getSessionVersion } from '#/features/auth/lib/accessTokenStore'
import { creditQueryKeys } from '#/features/credits/constants/creditQueryKeys'
import type { CreateOrderRequest, OrderAction } from '../types/errand.types'

export const useOrderActions = () => {
  const client = useQueryClient()
  const { userId, version } = useSession()
  const current = () => version === getSessionVersion()
  const checkSession = () => {
    if (!current()) throw new CanceledError('The login session changed')
    if (!userId) throw new Error('Sign in to continue')
  }
  const inSession = async <T>(operation: () => Promise<T>): Promise<T> => {
    checkSession()
    try {
      const result = await operation()
      checkSession()
      return result
    } catch (error) {
      checkSession()
      throw error
    }
  }
  const refreshLists = () => {
    void client.invalidateQueries({
      queryKey: errandQueryKeys.lists(userId),
    })
  }
  const refreshDetail = async (id: string) => {
    if (!current()) return
    refreshLists()
    await client.invalidateQueries({
      queryKey: errandQueryKeys.detail(userId, id),
      exact: true,
    })
    checkSession()
  }
  const create = useMutation({
    networkMode: 'always',
    gcTime: 0,
    mutationFn: (input: CreateOrderRequest) =>
      inSession(() => createOrder(input)),
    retry: false,
    onSettled: () => {
      if (!current()) return
      refreshLists()
      void client.invalidateQueries({ queryKey: creditQueryKeys.user(userId) })
    },
  })
  const retry = useMutation({
    networkMode: 'always',
    gcTime: 0,
    mutationFn: (id: string) => inSession(() => retryCreation(id)),
    retry: false,
    onSettled: (_result, _error, id) => {
      if (current())
        void client.invalidateQueries({
          queryKey: creditQueryKeys.user(userId),
        })
      return refreshDetail(id)
    },
  })
  const action = useMutation({
    networkMode: 'always',
    gcTime: 0,
    mutationFn: ({
      id,
      action: operation,
    }: {
      id: string
      action: OrderAction
    }) => inSession(() => actOnOrder(id, operation)),
    retry: false,
    onSettled: (_result, _error, { id }) => refreshDetail(id),
  })
  return { create, retry, action }
}
