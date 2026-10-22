import { axiosClient } from '#/lib/axiosClient'
import type { CreditBalanceResponse } from '../types/credit.types'

export const getCreditBalance = async (signal?: AbortSignal) => {
  const { data } = await axiosClient.get<CreditBalanceResponse>('/credits/me', {
    signal,
  })
  return data
}
