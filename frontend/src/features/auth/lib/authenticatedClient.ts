import axios, { AxiosHeaders, isAxiosError } from 'axios'
import type { InternalAxiosRequestConfig } from 'axios'

import {
  assertCurrentSession,
  clearSession,
  ensureSession,
  getSession,
  getSessionGeneration,
  SessionChangedError,
} from './authSession'

type AuthConfig = InternalAxiosRequestConfig & {
  authAttempt?: { generation: number; token: string; retried: boolean }
}

export const authenticatedClient = axios.create()

authenticatedClient.interceptors.request.use(async (config: AuthConfig) => {
  // Credentials are only sent to relative, same-origin business endpoints.
  const url = config.url ?? ''
  if (
    !url.startsWith('/') ||
    url.startsWith('//') ||
    config.baseURL ||
    /^\/auth(?:\/|\?|$)/.test(url)
  ) {
    throw new Error(
      'Use a relative business API path with the authenticated client.',
    )
  }

  const expected = config.authAttempt?.generation ?? getSessionGeneration()
  assertCurrentSession(expected)
  const session = await ensureSession()
  assertCurrentSession(expected)
  if (!session) throw new SessionChangedError()

  config.headers = AxiosHeaders.from(config.headers)
  config.headers.set('Authorization', `Bearer ${session.accessToken}`)
  config.authAttempt = {
    generation: expected,
    token: session.accessToken,
    retried: config.authAttempt?.retried ?? false,
  }

  return config
})

authenticatedClient.interceptors.response.use(
  (response) => {
    const attempt = (response.config as AuthConfig).authAttempt
    if (attempt) assertCurrentSession(attempt.generation)

    return response
  },
  async (error: unknown) => {
    if (!isAxiosError(error)) throw error

    const config = error.config as AuthConfig | undefined
    const attempt = config?.authAttempt
    if (!config || !attempt) throw error

    assertCurrentSession(attempt.generation)
    if (error.response?.status !== 401) throw error

    if (attempt.retried) {
      // A failed retry must not invalidate credentials refreshed since it was sent.
      if (getSession()?.accessToken !== attempt.token) throw error
      clearSession()
      throw new SessionChangedError()
    }

    const current = getSession()
    // A late 401 reuses credentials already refreshed by another request.
    await ensureSession(current?.accessToken === attempt.token)

    assertCurrentSession(attempt.generation)
    config.authAttempt = { ...attempt, retried: true }
    return authenticatedClient.request(config)
  },
)
