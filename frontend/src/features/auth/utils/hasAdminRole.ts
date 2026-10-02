import { getAccessToken } from '../lib/accessTokenStore'
import { decodeAccessToken } from './decodeAccessToken'

export const hasAdminRole = () => {
  const token = getAccessToken()
  const claims = decodeAccessToken(token)
  return claims?.roles?.includes('ADMIN') ?? false
}
