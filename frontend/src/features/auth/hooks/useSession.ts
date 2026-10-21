import { useMemo, useSyncExternalStore } from 'react'
import { getSessionSnapshot, subscribeSession } from '../lib/accessTokenStore'
import { decodeAccessToken } from '../utils/decodeAccessToken'

export const useSession = () => {
  const session = useSyncExternalStore(
    subscribeSession,
    getSessionSnapshot,
    getSessionSnapshot,
  )
  return useMemo(() => {
    const claims = decodeAccessToken(session.accessToken)
    return {
      userId: claims?.sub,
      roles: claims?.roles ?? [],
      version: session.version,
    }
  }, [session])
}
