import { afterEach, describe, expect, it, vi } from 'vitest'
import { axiosClient } from '#/lib/axiosClient'
import { refreshSession } from './refreshSession.api'

afterEach(() => vi.restoreAllMocks())

describe('refreshSession', () => {
  it('posts without exposing a refresh token and returns the access-token response', async () => {
    const tokens = { accessToken: 'token', expiresAt: '2026-09-24T00:00:00Z' }
    const post = vi
      .spyOn(axiosClient, 'post')
      .mockResolvedValue({ data: tokens })
    expect(await refreshSession()).toEqual(tokens)
    expect(post).toHaveBeenCalledExactlyOnceWith('/auth/refresh', undefined, {
      timeout: 15_000,
    })
  })
})
