import { getAccessToken } from '../lib/accessTokenStore'
import { decodeAccessToken } from './decodeAccessToken'

export const hasAdminRole = () => {
  try {
    const token = getAccessToken()
    const claims = decodeAccessToken(token)
    return claims?.roles?.includes('ADMIN') ?? false
  } catch {
    return false
  }
}
