import { useEffect } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import type { QueryClient } from '@tanstack/react-query'
import { connectRealtime } from '#/lib/realtime'
import { supplierKeys } from './useSuppliers'
import type { Supplier } from '../types/supplier.types'

export type SupplierChange =
  'CREATED' | 'UPDATED' | 'ACTIVATED' | 'DEACTIVATED' | 'IMAGE_CHANGED'

export type SupplierChangedPayload = {
  supplierId: string
  change: SupplierChange
}

export const applySupplierChanged = (
  queryClient: QueryClient,
  { supplierId, change }: SupplierChangedPayload,
): void => {
  if (change === 'ACTIVATED' || change === 'DEACTIVATED') {
    const active = change === 'ACTIVATED'

    queryClient.setQueryData<Supplier>(
      supplierKeys.detail(supplierId),
      (supplier) => (supplier ? { ...supplier, active } : supplier),
    )
    queryClient.setQueriesData<Supplier[]>(
      { queryKey: supplierKeys.lists() },
      (suppliers) =>
        suppliers?.map((supplier) =>
          supplier.id === supplierId ? { ...supplier, active } : supplier,
        ),
    )
  }

  void queryClient.invalidateQueries({ queryKey: supplierKeys.all })
}

export const useSupplierChanges = () => {
  const queryClient = useQueryClient()
  useEffect(() => {
    const socket = connectRealtime()
    socket.on('supplier.changed', (payload: SupplierChangedPayload) =>
      applySupplierChanged(queryClient, payload),
    )
    return () => {
      socket.disconnect()
    }
  }, [queryClient])
}
