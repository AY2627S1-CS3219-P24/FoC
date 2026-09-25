import { isAxiosError } from 'axios'

import { refreshSession } from '../api/refreshSession.api'
import { logoutUser } from '../api/logoutUser.api'
import type { AccessTokenResponse } from '../types/auth.types'

type SessionStatus =
  'normal' | 'requires-login' | 'logging-out' | 'logout-error' | 'logged-out'

const expiryMarginMs = 30_000
const logoutWaitMs = 10_000

let session: AccessTokenResponse | null = null
let generation = 0
let pending: Promise<AccessTokenResponse | null> | null = null
let logoutPending: Promise<boolean> | null = null
const refreshes = new Set<Promise<AccessTokenResponse | null>>()
const listeners = new Set<() => void>()
let snapshot: Readonly<{ status: SessionStatus }> = Object.freeze({
  status: 'normal',
})

const updateStatus = (status: SessionStatus) => {
  if (snapshot.status === status) return
  snapshot = Object.freeze({ status })
  listeners.forEach((listener) => listener())
}

export const subscribeSession = (listener: () => void) => {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

export const getSessionSnapshot = () => snapshot

export const getSessionGeneration = () => generation

export const getSession = (): AccessTokenResponse | null =>
  session && { ...session }

export const isLoggingOut = () =>
  snapshot.status === 'logging-out' || snapshot.status === 'logout-error'

export class SessionChangedError extends Error {
  constructor() {
    super('The session has changed. Please try again.')
    this.name = 'SessionChangedError'
  }
}

export const assertCurrentSession = (expected: number) => {
  if (expected !== generation || snapshot.status !== 'normal')
    throw new SessionChangedError()
}

export const establishSession = (value: AccessTokenResponse) => {
  session = { ...value }
  generation += 1
  pending = null
  updateStatus('normal')
}

// Recovery is blocked by default; tests opt in to simulate a fresh page load.
export const clearSession = (allowRecovery = false) => {
  session = null
  generation += 1
  pending = null
  updateStatus(allowRecovery ? 'normal' : 'requires-login')
}

const validSession = () =>
  session && Date.parse(session.expiresAt) > Date.now() + expiryMarginMs
    ? getSession()
    : null

export const ensureSession = (
  force = false,
): Promise<AccessTokenResponse | null> => {
  if (snapshot.status !== 'normal') return Promise.resolve(null)
  if (pending) return pending

  const current = validSession()
  if (!force && current) return Promise.resolve(current)

  const startedGeneration = generation

  const request = (async () => {
    try {
      const result = await refreshSession()
      if (generation !== startedGeneration) return validSession()

      if (
        !result.accessToken ||
        !(Date.parse(result.expiresAt) > Date.now() + expiryMarginMs)
      )
        throw new Error('Invalid session response')

      // Refresh updates credentials without changing the login identity.
      session = { ...result }
      return getSession()
    } catch (error) {
      if (generation !== startedGeneration) return validSession()
      if (isAxiosError(error) && error.response?.status === 401) {
        clearSession()
        return null
      }

      throw error
    }
  })()

  pending = request
  refreshes.add(request)

  const release = () => {
    refreshes.delete(request)
    if (pending === request) pending = null
  }

  void request.then(release, release)

  return request
}

const waitForRefreshes = async (
  requests: Array<Promise<AccessTokenResponse | null>>,
) => {
  let timer: ReturnType<typeof setTimeout> | undefined

  try {
    await Promise.race([
      Promise.allSettled(requests),
      new Promise<never>((_, reject) => {
        timer = setTimeout(
          () => reject(new Error('Session recovery is still pending')),
          logoutWaitMs,
        )
      }),
    ])
  } finally {
    clearTimeout(timer)
  }
}

export const logout = (): Promise<boolean> => {
  if (logoutPending) return logoutPending
  if (snapshot.status === 'logged-out') return Promise.resolve(true)

  // Capture outstanding refreshes before invalidating their in-memory results.
  const outstanding = [...refreshes]
  session = null
  generation += 1
  pending = null

  const startedGeneration = generation
  updateStatus('logging-out')

  const request = (async () => {
    try {
      await waitForRefreshes(outstanding)
      if (generation !== startedGeneration) return false

      await logoutUser()
      if (generation !== startedGeneration) return false

      updateStatus('logged-out')
      return true
    } catch {
      if (generation === startedGeneration) updateStatus('logout-error')
      return false
    }
  })()

  logoutPending = request

  void request.then(() => {
    if (logoutPending === request) logoutPending = null
  })

  return request
}
