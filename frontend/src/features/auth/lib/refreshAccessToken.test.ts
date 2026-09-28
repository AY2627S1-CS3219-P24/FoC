import { afterEach, expect, it, vi } from 'vitest'

import { refreshSession } from '../api/refreshSession.api'
import type { AccessTokenResponse } from '../types/auth.types'
import { getAccessToken, setAccessToken } from './accessTokenStore'
import { invalidateRefresh, refreshAccessToken } from './refreshAccessToken'
import { AuthRequestError } from './authRequest'

vi.mock('../api/refreshSession.api', () => ({ refreshSession: vi.fn() }))

afterEach(() => {
  setAccessToken(null)
  vi.mocked(refreshSession).mockReset()
})

it.each(['success', '401'])(
  'does not overwrite a new login with an obsolete refresh %s',
  async (outcome) => {
    let resolve!: (value: AccessTokenResponse) => void
    let reject!: (error: unknown) => void
    vi.mocked(refreshSession).mockReturnValue(
      new Promise((done, fail) => {
        resolve = done
        reject = fail
      }),
    )
    const pending = refreshAccessToken()
    invalidateRefresh()
    setAccessToken('new-login')

    if (outcome === 'success')
      resolve({ accessToken: 'stale', expiresAt: 'unused' })
    else reject(new AuthRequestError(401))

    expect(await pending).toBe('new-login')
    expect(getAccessToken()).toBe('new-login')
  },
)
