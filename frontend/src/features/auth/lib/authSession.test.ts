import { AxiosError, AxiosHeaders } from 'axios'
import { beforeEach, afterEach, describe, it, expect, vi } from 'vitest'

import { refreshSession } from '../api/refreshSession.api'
import { logoutUser } from '../api/logoutUser.api'
import {
  logout,
  getSessionSnapshot,
  getSessionGeneration,
  subscribeSession,
  clearSession,
  ensureSession,
  getSession,
  establishSession,
} from './authSession'
import type { AccessTokenResponse } from '../types/auth.types'

vi.mock('../api/refreshSession.api', () => ({ refreshSession: vi.fn() }))
vi.mock('../api/logoutUser.api', () => ({ logoutUser: vi.fn() }))

const refresh = vi.mocked(refreshSession)

const tokens = (
  accessToken = 'token',
  remaining = 300_000,
): AccessTokenResponse => ({
  accessToken,
  expiresAt: new Date(Date.now() + remaining).toISOString(),
})

const unauthorized = new AxiosError(
  'Unauthorized',
  undefined,
  undefined,
  undefined,
  {
    status: 401,
    statusText: '',
    data: {},
    headers: {},
    config: { headers: new AxiosHeaders() },
  },
)

beforeEach(() => {
  clearSession(true)
  refresh.mockReset()
  vi.mocked(logoutUser).mockReset()
})

afterEach(() => {
  clearSession(true)
  vi.restoreAllMocks()
  vi.useRealTimers()
})

describe('authSession', () => {
  it('keeps snapshots stable and preserves identity on refresh', async () => {
    establishSession(tokens())
    const snapshot = getSessionSnapshot()
    const generation = getSessionGeneration()
    const listener = vi.fn()
    const unsubscribe = subscribeSession(listener)
    refresh.mockResolvedValue(tokens('new'))
    await ensureSession(true)

    expect(getSessionGeneration()).toBe(generation)
    expect(getSessionSnapshot()).toBe(snapshot)
    expect(listener).not.toHaveBeenCalled()

    clearSession()

    expect(listener).toHaveBeenCalledTimes(1)
    expect(getSessionSnapshot()).toEqual({ status: 'requires-login' })

    unsubscribe()
  })

  it('waits for refresh before logout and blocks restoration after success', async () => {
    let resolve!: (value: AccessTokenResponse) => void
    refresh.mockReturnValue(
      new Promise((done) => {
        resolve = done
      }),
    )
    vi.mocked(logoutUser).mockResolvedValue()
    const refreshRequest = ensureSession()
    const first = logout()

    expect(logout()).toBe(first)
    expect(getSessionSnapshot().status).toBe('logging-out')
    expect(getSession()).toBeNull()
    expect(await ensureSession()).toBeNull()
    expect(logoutUser).not.toHaveBeenCalled()

    resolve(tokens('stale'))
    await refreshRequest

    expect(await first).toBe(true)
    expect(logoutUser).toHaveBeenCalledTimes(1)
    expect(getSessionSnapshot().status).toBe('logged-out')
    expect(await ensureSession()).toBeNull()
    expect(refresh).toHaveBeenCalledTimes(1)

    const newLogin = tokens('new-login')
    establishSession(newLogin)

    expect(await ensureSession()).toEqual(newLogin)
  })

  it('keeps recovery blocked after logout failure and retries the backend', async () => {
    vi.mocked(logoutUser)
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce()

    expect(await logout()).toBe(false)
    expect(getSessionSnapshot().status).toBe('logout-error')
    expect(await ensureSession()).toBeNull()
    expect(await logout()).toBe(true)
    expect(logoutUser).toHaveBeenCalledTimes(2)
  })

  it('allows retry while refresh is pending and sends logout when refresh times out', async () => {
    vi.useFakeTimers()
    refresh.mockImplementation(
      () =>
        new Promise((_, reject) => {
          setTimeout(
            () => reject(new AxiosError('timeout', 'ECONNABORTED')),
            15_000,
          )
        }),
    )
    vi.mocked(logoutUser).mockResolvedValue()
    const recovery = ensureSession()
    const first = logout()
    await vi.advanceTimersByTimeAsync(10_000)

    expect(await first).toBe(false)

    const retry = logout()

    expect(getSessionSnapshot().status).toBe('logging-out')
    expect(logoutUser).not.toHaveBeenCalled()
    expect(await ensureSession()).toBeNull()

    await vi.advanceTimersByTimeAsync(5_000)
    await recovery

    expect(await retry).toBe(true)
    expect(logoutUser).toHaveBeenCalledTimes(1)
    expect(refresh).toHaveBeenCalledTimes(1)
    expect(getSession()).toBeNull()
    expect(getSessionSnapshot().status).toBe('logged-out')
  })

  it('reuses a valid in-memory session', async () => {
    const value = tokens()
    establishSession(value)

    expect(await ensureSession()).toEqual(value)
    expect(refresh).not.toHaveBeenCalled()
  })

  it.each([-1000, 29_000])(
    'refreshes expired or nearly expired tokens (%s)',
    async (remaining) => {
      establishSession(tokens('old', remaining))
      const value = tokens('new')
      refresh.mockResolvedValue(value)

      expect(await ensureSession()).toEqual(value)
      expect(getSession()).toEqual(value)
    },
  )

  it('shares one pending refresh between concurrent callers', async () => {
    let resolve!: (value: AccessTokenResponse) => void
    refresh.mockReturnValue(
      new Promise((done) => {
        resolve = done
      }),
    )
    const first = ensureSession()
    const second = ensureSession()

    expect(first).toBe(second)
    expect(refresh).toHaveBeenCalledTimes(1)

    const value = tokens()
    resolve(value)

    expect(await first).toEqual(value)
  })

  it('clears rejected credentials on 401', async () => {
    establishSession(tokens('expired', -1000))
    refresh.mockRejectedValue(unauthorized)

    expect(await ensureSession()).toBeNull()
    expect(getSession()).toBeNull()
  })

  it('propagates service failure and permits retry', async () => {
    const error = new Error('offline')
    refresh.mockRejectedValueOnce(error).mockResolvedValueOnce(tokens())

    await expect(ensureSession()).rejects.toBe(error)
    expect(await ensureSession()).not.toBeNull()
    expect(refresh).toHaveBeenCalledTimes(2)
  })

  it.each(['success', '401', 'service error'])(
    'ignores stale %s after a newer login',
    async (outcome) => {
      let resolve!: (value: AccessTokenResponse) => void
      let reject!: (error: unknown) => void
      refresh.mockReturnValue(
        new Promise((done, fail) => {
          resolve = done
          reject = fail
        }),
      )
      const request = ensureSession()
      const newer = tokens('new-login')
      establishSession(newer)
      if (outcome === 'success') resolve(tokens('old-refresh'))
      else reject(outcome === '401' ? unauthorized : new Error('offline'))

      expect(await request).toEqual(newer)
      expect(getSession()).toEqual(newer)
    },
  )

  it('does not restore a cleared session from a stale response', async () => {
    let resolve!: (value: AccessTokenResponse) => void
    refresh.mockReturnValue(
      new Promise((done) => {
        resolve = done
      }),
    )
    const request = ensureSession()
    clearSession(true)
    resolve(tokens())

    expect(await request).toBeNull()
    expect(getSession()).toBeNull()
  })
})
