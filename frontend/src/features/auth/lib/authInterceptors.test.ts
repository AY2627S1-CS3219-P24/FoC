import { AxiosError } from 'axios'
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { beforeEach, afterEach, expect, it, vi } from 'vitest'

import { axiosClient } from '#/lib/axiosClient'
import { setupAuthInterceptors } from './authInterceptors'
import { AuthRequestError } from './authRequest'
import {
  clearSession,
  establishSession,
  getSession,
  ensureSession,
  SessionChangedError,
  logout,
} from './authSession'
import { refreshSession } from '../api/refreshSession.api'
import { logoutUser } from '../api/logoutUser.api'

vi.mock('../api/refreshSession.api', () => ({ refreshSession: vi.fn() }))
vi.mock('../api/logoutUser.api', () => ({ logoutUser: vi.fn() }))

const onLoginRequired = vi.fn()
let removeInterceptors: () => void

const refresh = vi.mocked(refreshSession)

const tokens = (accessToken = 'original', remaining = 300_000) => ({
  accessToken,
  expiresAt: new Date(Date.now() + remaining).toISOString(),
})

const response = (
  config: InternalAxiosRequestConfig,
  status = 200,
): AxiosResponse => ({
  config,
  status,
  statusText: '',
  headers: {},
  data: 'private result',
})

const fail = (config: InternalAxiosRequestConfig, status: number): never => {
  throw new AxiosError(
    'Failed',
    undefined,
    config,
    undefined,
    response(config, status),
  )
}

beforeEach(() => {
  onLoginRequired.mockReset()
  removeInterceptors = setupAuthInterceptors(axiosClient, onLoginRequired)
  clearSession(true)
  refresh.mockReset()
  vi.mocked(logoutUser).mockReset()
  establishSession(tokens())
})

afterEach(() => {
  removeInterceptors()
  clearSession(true)
  vi.restoreAllMocks()
})

it('adds Bearer credentials and refreshes before an expired-token request', async () => {
  establishSession(tokens('expired', -1))
  refresh.mockResolvedValue(tokens('fresh'))
  const adapter = vi.fn(async (config: InternalAxiosRequestConfig) =>
    response(config),
  )
  await axiosClient.get('/users/test', { adapter })

  expect(adapter.mock.calls[0][0].headers.get('Authorization')).toBe(
    'Bearer fresh',
  )
  expect(refresh).toHaveBeenCalledTimes(1)
})

it('forces refresh for a rejected, unexpired token and retries only once', async () => {
  refresh.mockResolvedValue(tokens('fresh'))
  const seen: Array<string> = []
  const adapter = vi.fn(async (config: InternalAxiosRequestConfig) => {
    seen.push(String(config.headers.get('Authorization')))
    if (seen.length === 1) fail(config, 401)
    return response(config)
  })
  await axiosClient.get('/users/test', { adapter })

  expect(seen).toEqual(['Bearer original', 'Bearer fresh'])
  expect(refresh).toHaveBeenCalledTimes(1)
})

it('blocks automatic recovery after a second 401', async () => {
  refresh.mockResolvedValue(tokens('fresh'))
  const adapter = vi.fn(async (config: InternalAxiosRequestConfig) =>
    fail(config, 401),
  )

  await expect(
    axiosClient.get('/users/test', { adapter }),
  ).rejects.toBeInstanceOf(SessionChangedError)
  expect(adapter).toHaveBeenCalledTimes(2)
  expect(onLoginRequired).toHaveBeenCalledTimes(1)
  expect(await ensureSession()).toBeNull()
  expect(refresh).toHaveBeenCalledTimes(1)
})

it('preserves a newer token when a retried request returns a late 401', async () => {
  const t2 = tokens('T2')
  refresh.mockResolvedValueOnce(tokens('T1')).mockResolvedValueOnce(t2)
  let rejectRetry!: (error: unknown) => void
  let retryConfig!: InternalAxiosRequestConfig
  const sent: Array<string> = []
  const adapter = vi.fn(async (config: InternalAxiosRequestConfig) => {
    sent.push(String(config.headers.get('Authorization')))
    if (sent.length === 1) return fail(config, 401)
    retryConfig = config
    return new Promise<AxiosResponse>((_, reject) => {
      rejectRetry = reject
    })
  })
  const request = axiosClient.get('/users/test', { adapter })
  await vi.waitFor(() => expect(retryConfig).toBeDefined())

  expect(sent).toEqual(['Bearer original', 'Bearer T1'])

  // Another caller refreshes within the same login while T1's retry is pending.
  await ensureSession({ forceRefresh: true })
  const lateError = new AxiosError(
    'Failed',
    undefined,
    retryConfig,
    undefined,
    response(retryConfig, 401),
  )
  const assertion = expect(request).rejects.toBe(lateError)
  rejectRetry(lateError)
  await assertion

  expect(getSession()).toEqual(t2)
  expect(onLoginRequired).not.toHaveBeenCalled()
  expect(await ensureSession()).toEqual(t2)
  expect(adapter).toHaveBeenCalledTimes(2)
  expect(refresh).toHaveBeenCalledTimes(2)
})

it('shares refresh for concurrent 401s', async () => {
  let resolve!: (value: ReturnType<typeof tokens>) => void
  refresh.mockReturnValue(
    new Promise((done) => {
      resolve = done
    }),
  )
  const adapter = vi.fn(async (config: InternalAxiosRequestConfig) => {
    if (config.headers.get('Authorization') === 'Bearer original')
      fail(config, 401)
    return response(config)
  })
  const first = axiosClient.get('/users/a', { adapter })
  const second = axiosClient.get('/users/b', { adapter })
  await vi.waitFor(() => expect(refresh).toHaveBeenCalledTimes(1))
  resolve(tokens('fresh'))
  await Promise.all([first, second])

  expect(refresh).toHaveBeenCalledTimes(1)
  expect(adapter).toHaveBeenCalledTimes(4)
})

it('reuses a newer token for a late 401', async () => {
  let rejectLate!: (error: unknown) => void
  let lateConfig!: InternalAxiosRequestConfig
  refresh.mockResolvedValue(tokens('fresh'))
  const adapter = vi.fn(async (config: InternalAxiosRequestConfig) => {
    if (config.headers.get('Authorization') === 'Bearer fresh')
      return response(config)
    if (config.url === '/users/late') {
      lateConfig = config
      return new Promise<AxiosResponse>((_, reject) => {
        rejectLate = reject
      })
    }
    return fail(config, 401)
  })
  const late = axiosClient.get('/users/late', { adapter })
  await vi.waitFor(() => expect(lateConfig).toBeDefined())
  await axiosClient.get('/users/early', { adapter })
  rejectLate(
    new AxiosError(
      'Failed',
      undefined,
      lateConfig,
      undefined,
      response(lateConfig, 401),
    ),
  )
  await late

  expect(refresh).toHaveBeenCalledTimes(1)
})

it.each([403, 502])('does not refresh HTTP %s', async (status) => {
  await expect(
    axiosClient.get('/users/test', {
      adapter: async (config) => fail(config, status),
    }),
  ).rejects.toBeInstanceOf(AxiosError)
  expect(refresh).not.toHaveBeenCalled()
  expect(onLoginRequired).not.toHaveBeenCalled()
})

it('preserves the session on refresh service failure', async () => {
  const error = new Error('offline')
  refresh.mockRejectedValue(error)

  await expect(
    axiosClient.get('/users/test', {
      adapter: async (config) => fail(config, 401),
    }),
  ).rejects.toBe(error)
  expect(onLoginRequired).not.toHaveBeenCalled()
})

it.each(['success', '401'])(
  'rejects old %s responses after another login',
  async (outcome) => {
    let finish!: () => void
    const adapter = vi.fn(
      (config: InternalAxiosRequestConfig) =>
        new Promise<AxiosResponse>((resolve, reject) => {
          finish = () =>
            outcome === 'success'
              ? resolve(response(config))
              : reject(
                  new AxiosError(
                    'Failed',
                    undefined,
                    config,
                    undefined,
                    response(config, 401),
                  ),
                )
        }),
    )
    const request = axiosClient.get('/users/test', { adapter })
    await vi.waitFor(() => expect(adapter).toHaveBeenCalledTimes(1))
    establishSession(tokens('other-login'))
    const assertion =
      expect(request).rejects.toBeInstanceOf(SessionChangedError)
    finish()
    await assertion

    expect(getSession()?.accessToken).toBe('other-login')
    expect(refresh).not.toHaveBeenCalled()
  },
)

it('rejects new requests when recovery is blocked', async () => {
  clearSession()
  const adapter = vi.fn(async (config: InternalAxiosRequestConfig) =>
    response(config),
  )

  await expect(
    axiosClient.get('/users/test', { adapter }),
  ).rejects.toBeInstanceOf(SessionChangedError)
  expect(adapter).not.toHaveBeenCalled()
  expect(refresh).not.toHaveBeenCalled()
})

it.each(['/auth/refresh', 'https://example.com/users', '//example.com/users'])(
  'rejects non-business destination %s',
  async (url) => {
    const adapter = vi.fn(async (config: InternalAxiosRequestConfig) =>
      response(config),
    )

    await expect(axiosClient.get(url, { adapter })).rejects.toThrow(
      'relative business API path',
    )
    expect(adapter).not.toHaveBeenCalled()
  },
)

it('requires login when refresh rejects credentials', async () => {
  const refreshError = new AuthRequestError(401)
  refresh.mockRejectedValue(refreshError)
  const adapter = vi.fn(async (config: InternalAxiosRequestConfig) =>
    fail(config, 401),
  )

  await expect(
    axiosClient.get('/users/test', { adapter }),
  ).rejects.toBeInstanceOf(SessionChangedError)
  expect(adapter).toHaveBeenCalledTimes(1)
  expect(onLoginRequired).toHaveBeenCalledTimes(1)
  expect(await ensureSession()).toBeNull()
  expect(refresh).toHaveBeenCalledTimes(1)
})

it('rejects an in-flight business result after logout starts and blocks new requests', async () => {
  let finish!: () => void
  let finishLogout!: () => void
  vi.mocked(logoutUser).mockReturnValue(
    new Promise<void>((resolve) => {
      finishLogout = resolve
    }),
  )
  const adapter = vi.fn(
    (config: InternalAxiosRequestConfig) =>
      new Promise<AxiosResponse>((resolve) => {
        finish = () => resolve(response(config))
      }),
  )
  const request = axiosClient.get('/users/test', { adapter })
  await vi.waitFor(() => expect(adapter).toHaveBeenCalledTimes(1))
  const exiting = logout()
  const assertion = expect(request).rejects.toBeInstanceOf(SessionChangedError)
  finish()
  await assertion

  await expect(
    axiosClient.get('/users/test', { adapter }),
  ).rejects.toBeInstanceOf(SessionChangedError)
  expect(adapter).toHaveBeenCalledTimes(1)

  await vi.waitFor(() => expect(logoutUser).toHaveBeenCalledTimes(1))
  finishLogout()
  await exiting
})
