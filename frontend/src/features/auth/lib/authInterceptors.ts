import { AxiosHeaders, isAxiosError } from 'axios'
import type {
  AxiosInstance,
  AxiosResponse,
  InternalAxiosRequestConfig,
} from 'axios'

import {
  assertCurrentSession,
  clearSession,
  ensureSession,
  getSession,
  getSessionVersion,
  isLoggingOut,
  SessionChangedError,
} from './authSession'

type AuthConfig = InternalAxiosRequestConfig & {
  authContext?: {
    sessionVersion: number
    accessToken: string
    hasRetried: boolean
  }
}

export const setupAuthInterceptors = (
  client: AxiosInstance,
  onLoginRequired: () => void,
) => {
  const requireLoginIfMissing = (session: ReturnType<typeof getSession>) => {
    if (!session && !isLoggingOut() && !getSession()) onLoginRequired()
  }

  const attachAccessToken = async (config: AuthConfig) => {
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

    const expected = config.authContext?.sessionVersion ?? getSessionVersion()
    assertCurrentSession(expected)
    const session = await ensureSession()
    requireLoginIfMissing(session)
    assertCurrentSession(expected)
    if (!session) throw new SessionChangedError()

    config.headers = AxiosHeaders.from(config.headers)
    config.headers.set('Authorization', `Bearer ${session.accessToken}`)
    config.authContext = {
      sessionVersion: expected,
      accessToken: session.accessToken,
      hasRetried: config.authContext?.hasRetried ?? false,
    }

    return config
  }

  const checkResponseSession = (response: AxiosResponse) => {
    const context = (response.config as AuthConfig).authContext
    if (context) assertCurrentSession(context.sessionVersion)

    return response
  }

  const handleRequestError = async (error: unknown) => {
    if (!isAxiosError(error)) throw error

    const config = error.config as AuthConfig | undefined
    const context = config?.authContext
    if (!config || !context) throw error

    assertCurrentSession(context.sessionVersion)
    if (error.response?.status !== 401) throw error

    if (context.hasRetried) {
      // A failed retry must not invalidate credentials refreshed since it was sent.
      if (getSession()?.accessToken !== context.accessToken) throw error
      clearSession()
      onLoginRequired()
      throw new SessionChangedError()
    }

    const current = getSession()
    // A late 401 reuses credentials already refreshed by another request.
    const session = await ensureSession({
      forceRefresh: current?.accessToken === context.accessToken,
    })
    requireLoginIfMissing(session)

    assertCurrentSession(context.sessionVersion)
    config.authContext = { ...context, hasRetried: true }
    return client.request(config)
  }

  const requestId = client.interceptors.request.use(attachAccessToken)
  const responseId = client.interceptors.response.use(
    checkResponseSession,
    handleRequestError,
  )

  return () => {
    client.interceptors.request.eject(requestId)
    client.interceptors.response.eject(responseId)
  }
}
