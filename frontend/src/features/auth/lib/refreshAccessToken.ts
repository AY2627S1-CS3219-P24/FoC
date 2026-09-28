import { refreshSession } from '../api/refreshSession.api'
import { getAccessToken, setAccessToken } from './accessTokenStore'

let refreshPromise: Promise<string | null> | null = null
let discardResult: (() => void) | null = null

export const getPendingRefresh = () => refreshPromise

// Prevent an obsolete refresh from overwriting a new login or ended authentication.
export const invalidateRefresh = () => {
  discardResult?.()
}

export const refreshAccessToken = (): Promise<string | null> => {
  if (refreshPromise) return refreshPromise

  let invalidated = false
  discardResult = () => {
    invalidated = true
  }

  refreshPromise = refreshSession()
    .then(({ accessToken }) => {
      if (invalidated) return getAccessToken()
      setAccessToken(accessToken)
      return accessToken
    })
    .catch((error: unknown) => {
      if (invalidated) return getAccessToken()
      throw error
    })
    .finally(() => {
      refreshPromise = null
      discardResult = null
    })

  return refreshPromise
}
