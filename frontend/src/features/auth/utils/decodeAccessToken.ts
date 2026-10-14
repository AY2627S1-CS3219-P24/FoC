import { jwtDecode } from 'jwt-decode'
import type { UserRole } from '#/features/users/types/user.types'

export type AccessTokenClaims = {
  roles?: UserRole[]
}

export const decodeAccessToken = (
  accessToken: string | null,
): AccessTokenClaims | null => {
  if (!accessToken) return null

  return jwtDecode<AccessTokenClaims>(accessToken)
}
