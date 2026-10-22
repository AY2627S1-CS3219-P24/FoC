import { axiosClient } from '#/lib/axiosClient'
import type { Page } from '#/types/page.types'
import type { Errand, ErrandActionResponse } from '../types/errand.types'

export const listAvailableErrands = async (
  page: number,
  signal?: AbortSignal,
) => {
  const { data } = await axiosClient.get<Page<Errand>>('/orders', {
    params: { page, size: 20 },
    signal,
  })
  return data
}

export const listCourierErrands = async (
  page: number,
  signal?: AbortSignal,
) => {
  const { data } = await axiosClient.get<Page<Errand>>('/orders/mine', {
    params: { as: 'courier', page, size: 20 },
    signal,
  })
  return data
}

export const getErrand = async (id: string, signal?: AbortSignal) => {
  const { data } = await axiosClient.get<Errand>(`/orders/${id}`, { signal })
  return data
}

export const acceptErrand = async (id: string) => {
  const { data } = await axiosClient.post<ErrandActionResponse>(
    `/orders/${id}/accept`,
  )
  return data
}

export const pickUpErrand = async (id: string) => {
  const { data } = await axiosClient.post<ErrandActionResponse>(
    `/orders/${id}/pick-up`,
  )
  return data
}
