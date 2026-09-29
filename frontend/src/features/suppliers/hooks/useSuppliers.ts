import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query'
import {
  createSupplier,
  getSupplier,
  listSuppliers,
  setSupplierActive,
  updateSupplier,
} from '../api/suppliers.api'
import { supplierQueryKeys } from '../constants/supplierQueryKeys'
import type { SupplierRequest } from '../types/supplier.types'

export const useSuppliers = (includeInactive: boolean) =>
  useQuery({
    queryKey: supplierQueryKeys.list(includeInactive),
    queryFn: () => listSuppliers(includeInactive),
    // When toggle the "show inactive suppliers" need to keep the previous supplier listing table
    placeholderData: keepPreviousData,
  })

export const useSupplier = (id: string) =>
  useQuery({
    queryKey: supplierQueryKeys.detail(id),
    queryFn: () => getSupplier(id),
  })

const useInvalidateSuppliers = () => {
  const queryClient = useQueryClient()
  return () =>
    queryClient.invalidateQueries({ queryKey: supplierQueryKeys.all })
}

export const useCreateSupplier = () => {
  const invalidate = useInvalidateSuppliers()
  return useMutation({
    mutationFn: (request: SupplierRequest) => createSupplier(request),
    onSuccess: invalidate,
  })
}

export const useUpdateSupplier = (id: string) => {
  const invalidate = useInvalidateSuppliers()
  return useMutation({
    mutationFn: (request: SupplierRequest) => updateSupplier(id, request),
    onSuccess: invalidate,
  })
}

export const useSetSupplierActive = () => {
  const invalidate = useInvalidateSuppliers()
  return useMutation({
    mutationFn: ({ id, active }: { id: string; active: boolean }) =>
      setSupplierActive(id, active),
    onSuccess: invalidate,
  })
}
