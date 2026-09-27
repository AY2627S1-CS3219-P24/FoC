import { AuthRequestError } from './authRequest'
import { beforeEach, afterEach, describe, it, expect, vi } from 'vitest'

import { refreshSession } from '../api/refreshSession.api'
import { logoutUser } from '../api/logoutUser.api'
import {
  logout,
  getSessionVersion,
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

const unauthorized = new AuthRequestError(401)

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
  it('preserves login identity when refreshing credentials', async () => {
    establishSession(tokens())
    const sessionVersion = getSessionVersion()
    refresh.mockResolvedValue(tokens('new'))
    await ensureSession({ forceRefresh: true })

    expect(getSessionVersion()).toBe(sessionVersion)
    expect(getSession()?.accessToken).toBe('new')
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
    expect(getSession()).toBeNull()
    expect(await ensureSession()).toBeNull()
    expect(logoutUser).not.toHaveBeenCalled()

    resolve(tokens('stale'))
    await refreshRequest

    expect(await first).toBe('completed')
    expect(logoutUser).toHaveBeenCalledTimes(1)
    expect(await ensureSession()).toBeNull()
    expect(refresh).toHaveBeenCalledTimes(1)

    const newLogin = tokens('new-login')
    establishSession(newLogin)

    expect(await ensureSession()).toEqual(newLogin)
  })

  it('finishes local logout even when the backend fails', async () => {
    vi.mocked(logoutUser).mockRejectedValue(new Error('offline'))

    expect(await logout()).toBe('completed')
    expect(await ensureSession()).toBeNull()
    expect(refresh).not.toHaveBeenCalled()
  })

  it('continues logout when an outstanding refresh times out', async () => {
    vi.useFakeTimers()
    refresh.mockImplementation(
      () =>
        new Promise((_, reject) => {
          setTimeout(() => reject(new AuthRequestError()), 15_000)
        }),
    )
    const recovery = ensureSession()
    const exiting = logout()
    await vi.advanceTimersByTimeAsync(10_000)
    expect(logoutUser).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(5_000)
    await recovery
    expect(await exiting).toBe('completed')
    expect(logoutUser).toHaveBeenCalledTimes(1)
  })

  it('does not complete an old logout over a new login', async () => {
    let finish!: () => void
    vi.mocked(logoutUser).mockReturnValue(
      new Promise<void>((resolve) => {
        finish = resolve
      }),
    )
    const exiting = logout()
    await vi.waitFor(() => expect(logoutUser).toHaveBeenCalledTimes(1))
    establishSession(tokens('new-login'))
    finish()

    expect(await exiting).toBe('session-changed')
    expect(getSession()?.accessToken).toBe('new-login')
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
