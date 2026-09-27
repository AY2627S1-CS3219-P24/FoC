import { AuthRequestError } from './authRequest'

import { refreshSession } from '../api/refreshSession.api'
import { logoutUser } from '../api/logoutUser.api'
import type { AccessTokenResponse } from '../types/auth.types'

type LogoutResult = 'completed' | 'session-changed'

const expiryMarginMs = 30_000

// Access token and expiry time kept in memory.
let currentSession: AccessTokenResponse | null = null

// Identifies the current session; changes on login or when the session is cleared
let sessionVersion = 0

// Current refresh request shared by concurrent callers
let refreshPromise: Promise<AccessTokenResponse | null> | null = null

// Current logout request shared by repeated calls
let logoutPromise: Promise<LogoutResult> | null = null

// Track refresh requests until they finish so logout can wait after refreshPromise is cleared.
const activeRefreshRequests = new Set<Promise<AccessTokenResponse | null>>()

// Whether this page may restore the session using the refresh cookie
let recoveryAllowed = true

export const getSessionVersion = () => sessionVersion

export const getSession = (): AccessTokenResponse | null =>
  currentSession && { ...currentSession }

export const isLoggingOut = () => logoutPromise !== null

export class SessionChangedError extends Error {
  constructor() {
    super('The session has changed. Please try again.')
    this.name = 'SessionChangedError'
  }
}

// Stop work if the session changed or this page cannot restore it
export const assertCurrentSession = (expectedVersion: number) => {
  if (expectedVersion !== sessionVersion || !recoveryAllowed)
    throw new SessionChangedError()
}

// Save the access token and start a new in-memory session after login succeeds
export const establishSession = (value: AccessTokenResponse) => {
  currentSession = { ...value }
  sessionVersion += 1
  refreshPromise = null
  recoveryAllowed = true
}

// Clear the in-memory session and block recovery unless explicitly allowed
export const clearSession = (allowRecovery = false) => {
  currentSession = null
  sessionVersion += 1
  refreshPromise = null
  recoveryAllowed = allowRecovery
}

// Return the session only if its access token stays valid for more than 30 seconds
const getUsableSession = () =>
  currentSession &&
  Date.parse(currentSession.expiresAt) > Date.now() + expiryMarginMs
    ? getSession()
    : null

// Send a refresh request and apply its token only if the session has not changed.
const refreshCurrentSession = async (startedVersion: number) => {
  try {
    const result = await refreshSession()
    if (sessionVersion !== startedVersion) return getUsableSession()

    // check if accessToken is not empty with expiry time more than 30s
    if (
      !result.accessToken ||
      !(Date.parse(result.expiresAt) > Date.now() + expiryMarginMs)
    )
      throw new Error('Invalid session response')

    currentSession = { ...result }
    return getSession()
  } catch (error) {
    if (sessionVersion !== startedVersion) return getUsableSession()
    if (error instanceof AuthRequestError && error.status === 401) {
      clearSession()
      return null
    }

    throw error
  }
}

// Return a usable session, reusing or starting a refresh when needed
export const ensureSession = ({
  forceRefresh = false,
}: { forceRefresh?: boolean } = {}): Promise<AccessTokenResponse | null> => {
  if (!recoveryAllowed) {
    return Promise.resolve(null) // Session recovery is blocked, so don't try to refresh
  }

  if (refreshPromise) {
    return refreshPromise // A refresh is already in progress, so return the existing promise
  }

  const usableSession = getUsableSession()
  if (!forceRefresh && usableSession) return Promise.resolve(usableSession)

  const request = refreshCurrentSession(sessionVersion)
  refreshPromise = request
  activeRefreshRequests.add(request)

  const release = () => {
    activeRefreshRequests.delete(request)
    if (refreshPromise === request) refreshPromise = null
  }

  void request.then(release, release)

  return request
}

// wait for active refreshes to finish, then request server logout and clear the refresh cookie
const completeLogout = async (
  refreshRequests: Array<Promise<AccessTokenResponse | null>>,
  startedVersion: number,
): Promise<LogoutResult> => {
  await Promise.allSettled(refreshRequests)
  if (sessionVersion !== startedVersion) return 'session-changed'

  try {
    await logoutUser()
  } catch {
    // A server failure must not prevent the caller from finishing local logout.
  }

  return sessionVersion === startedVersion ? 'completed' : 'session-changed'
}

// Clear the local session and start one shared logout operation.
export const logout = (): Promise<LogoutResult> => {
  if (logoutPromise) return logoutPromise

  const refreshRequests = [...activeRefreshRequests]
  clearSession()

  const request = completeLogout(refreshRequests, sessionVersion)
  logoutPromise = request

  void request.then(() => {
    if (logoutPromise === request) logoutPromise = null
  })

  return request
}
