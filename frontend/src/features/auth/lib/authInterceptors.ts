import { AxiosHeaders, isAxiosError } from 'axios'
import type { AxiosInstance, InternalAxiosRequestConfig } from 'axios'

import { getAccessToken } from './accessTokenStore'
import { refreshAccessToken } from './refreshAccessToken'
import { AuthRequestError } from './authRequest'

type AuthConfig = InternalAxiosRequestConfig & {
  sentAccessToken?: string | null
  authRetried?: boolean
}

type AuthEvents = {
  canRefresh: () => boolean
  authenticationFailed: () => void
}

export const setupAuthInterceptors = (
  client: AxiosInstance,
  auth: AuthEvents,
) => {
  const requestId = client.interceptors.request.use((config: AuthConfig) => {
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
  })

  const responseId = client.interceptors.response.use(
    undefined,
    async (error: unknown) => {
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
            auth.canRefresh()
          ) {
            auth.authenticationFailed()
          }
          throw refreshError
        }
      }

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
