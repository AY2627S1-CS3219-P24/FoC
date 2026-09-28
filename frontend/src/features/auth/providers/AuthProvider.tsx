import {
  createContext,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react'
import type { ReactNode } from 'react'

import { axiosClient } from '#/lib/axiosClient'
import { logoutUser } from '../api/logoutUser.api'
import { getAccessToken, setAccessToken } from '../lib/accessTokenStore'
import { AuthRequestError } from '../lib/authRequest'
import { setupAuthInterceptors } from '../lib/authInterceptors'
import {
  getPendingRefresh,
  invalidateRefresh,
  refreshAccessToken,
} from '../lib/refreshAccessToken'

export type AuthOperations = {
  ensureAuthenticated: () => Promise<boolean>
  completeLogin: (accessToken: string) => void
  logout: () => Promise<void>
}

const AuthContext = createContext<
  (AuthOperations & { invalidation: number }) | null
>(null)

export const useAuth = () => {
  const auth = useContext(AuthContext)
  if (!auth) throw new Error('useAuth requires AuthProvider')
  return auth
}

export const AuthProvider = ({ children }: { children: ReactNode }) => {
  // An explicit end to authentication must not immediately trigger cookie recovery.
  const authenticationEnded = useRef(false)
  const exiting = useRef<Promise<void> | null>(null)
  const [invalidation, setInvalidation] = useState(0)

  const operations = useMemo(() => {
    const canRefresh = () => !authenticationEnded.current && !exiting.current

    const authenticationFailed = () => {
      if (authenticationEnded.current) return
      authenticationEnded.current = true
      invalidateRefresh()
      setAccessToken(null)
      setInvalidation((value) => value + 1)
    }

    const ensureAuthenticated = async () => {
      // A failed logout is handled by the page, not the route recovery screen.
      await exiting.current?.catch(() => undefined)
      if (authenticationEnded.current) return false
      if (getAccessToken()) return true

      try {
        await refreshAccessToken()
        await exiting.current?.catch(() => undefined)
        return canRefresh() && Boolean(getAccessToken())
      } catch (error) {
        if (error instanceof AuthRequestError && error.status === 401) {
          authenticationFailed()
          return false
        }
        throw error
      }
    }

    const completeLogin = (accessToken: string) => {
      invalidateRefresh()
      setAccessToken(accessToken)
      authenticationEnded.current = false
    }

    const logout = (): Promise<void> => {
      if (exiting.current) return exiting.current

      const pendingRefresh = getPendingRefresh()

      // Serialize cookie rotation and logout. The API adapters bound each request.
      exiting.current = (async () => {
        try {
          await pendingRefresh?.catch((error: unknown) => {
            // A refresh rejection remains an independent authentication failure.
            if (error instanceof AuthRequestError && error.status === 401) {
              authenticationFailed()
            }
          })
          await logoutUser()
          authenticationEnded.current = true
          setAccessToken(null)
        } finally {
          exiting.current = null
        }
      })()

      return exiting.current
    }

    return {
      ensureAuthenticated,
      completeLogin,
      logout,
      authenticationFailed,
      canRefresh,
    }
  }, [])

  useEffect(() => setupAuthInterceptors(axiosClient, operations), [operations])

  const value = useMemo(
    () => ({
      ensureAuthenticated: operations.ensureAuthenticated,
      completeLogin: operations.completeLogin,
      logout: operations.logout,
      invalidation,
    }),
    [operations, invalidation],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
