// TODO: need to import in authentication part here
import type { Supplier, SupplierRequest } from '../types/supplier.types'

export const listSuppliers = async (includeInactive: boolean) => {
  const { data } = await supplierClient.get<Array<Supplier>>('/suppliers', {
    params: { includeInactive },
  })
  return data
}

export const getSupplier = async (id: string) => {
  const { data } = await supplierClient.get<Supplier>(`/suppliers/${id}`)
  return data
}

export const createSupplier = async (request: SupplierRequest) => {
  const { data } = await supplierClient.post<Supplier>('/suppliers', request)
  return data
}

export const updateSupplier = async (id: string, request: SupplierRequest) => {
  const { data } = await supplierClient.put<Supplier>(
    `/suppliers/${id}`,
    request,
  )
  return data
}

export const setSupplierActive = async (id: string, active: boolean) => {
  const action = active ? 'activate' : 'deactivate'
  const { data } = await supplierClient.post<Supplier>(
    `/suppliers/${id}/${action}`,
  )
  return data
}
