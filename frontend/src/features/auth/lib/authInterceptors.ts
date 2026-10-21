import { AxiosHeaders, CanceledError, isAxiosError } from 'axios'
import type { AxiosInstance, InternalAxiosRequestConfig } from 'axios'

import { getAccessToken, getSessionVersion } from './accessTokenStore'
import { refreshAccessToken } from './refreshAccessToken'
import { AuthRequestError } from './authRequest'

type AuthConfig = InternalAxiosRequestConfig & {
  sentAccessToken?: string | null
  authRetried?: boolean
  sessionVersion?: number
}

type AuthEvents = {
  canRefresh: () => boolean
  authenticationFailed: () => void
}

export const setupAuthInterceptors = (
  client: AxiosInstance,
  auth: AuthEvents,
) => {
  const requestId = client.interceptors.request.use(
    (config: AuthConfig) => {
      config.sessionVersion ??= getSessionVersion()
      if (config.sessionVersion !== getSessionVersion())
        throw new CanceledError('The login session changed')
      // Restrict automatic credentials to this application's business APIs.
      const url = config.url ?? ''
      const baseURL = config.baseURL ?? ''
      if (
        !url.startsWith('/') ||
        url.startsWith('//') ||
        (baseURL !== '' && baseURL !== '/api') ||
        /^\/(?:api\/)?auth(?:\/|\?|$)/.test(url)
      ) {
        throw new Error(
          'Use a relative business API path with the authenticated client.',
        )
      }

      const token = getAccessToken()
      config.headers = AxiosHeaders.from(config.headers)
      if (token) config.headers.set('Authorization', `Bearer ${token}`)
      else config.headers.delete('Authorization')
      config.sentAccessToken = token
      return config
    },
    (error: unknown) => {
      throw error
    },
    { synchronous: true },
  )

  const responseId = client.interceptors.response.use(
    (response) => {
      if (
        (response.config as AuthConfig).sessionVersion !== getSessionVersion()
      )
        throw new CanceledError('The login session changed')
      return response
    },
    async (error: unknown) => {
      if (
        isAxiosError(error) &&
        error.config &&
        (error.config as AuthConfig).sessionVersion !== getSessionVersion()
      )
        throw new CanceledError('The login session changed')
      if (
        !isAxiosError(error) ||
        error.response?.status !== 401 ||
        !error.config
      )
        throw error
      const config = error.config as AuthConfig
      if (!auth.canRefresh()) throw error

      const currentToken = getAccessToken()
      if (config.authRetried) {
        // A late retry failure must not discard credentials obtained since it was sent.
        if (currentToken === config.sentAccessToken) auth.authenticationFailed()
        throw error
      }

      let token = currentToken
      if (!token || token === config.sentAccessToken) {
        try {
          token = await refreshAccessToken()
        } catch (refreshError) {
          if (
            refreshError instanceof AuthRequestError &&
            refreshError.status === 401 &&
            config.sessionVersion === getSessionVersion() &&
            auth.canRefresh()
          ) {
            auth.authenticationFailed()
          }
          throw refreshError
        }
      }

      if (config.sessionVersion !== getSessionVersion())
        throw new CanceledError('The login session changed')
      if (!token || !auth.canRefresh()) throw error
      config.authRetried = true
      return client.request(config)
    },
  )

  return () => {
    client.interceptors.request.eject(requestId)
    client.interceptors.response.eject(responseId)
  }
}
