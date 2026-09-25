import { axiosClient } from '#/lib/axiosClient'
import type { AccessTokenResponse, LoginRequest } from '../types/auth.types'

export const loginUser = async (
  request: LoginRequest,
): Promise<AccessTokenResponse> => {
  const { email, password } = request
  const response = await axiosClient.post<AccessTokenResponse>('/auth/login', {
    email,
    password,
  })

  return response.data
}
