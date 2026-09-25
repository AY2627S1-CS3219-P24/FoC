import { axiosClient } from '#/lib/axiosClient'
import type { AccessTokenResponse } from '../types/auth.types'

export const refreshSession = async (): Promise<AccessTokenResponse> => {
  // Bound recovery so logout cannot keep waiting on a hung browser request.
  const response = await axiosClient.post<AccessTokenResponse>(
    '/auth/refresh',
    undefined,
    { timeout: 15_000 },
  )
  return response.data
}
