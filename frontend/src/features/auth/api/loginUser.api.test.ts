import { afterEach, describe, expect, it, vi } from 'vitest'
import { axiosClient } from '#/lib/axiosClient'
import { loginUser } from './loginUser.api'

const request = { email: 'jamie@example.com', password: ' password123 ' }
const tokens = { accessToken: 'test-token', expiresAt: '2026-09-23T14:00:00Z' }

afterEach(() => vi.restoreAllMocks())

describe('loginUser', () => {
  it('sends only login fields and preserves the password', async () => {
    const post = vi
      .spyOn(axiosClient, 'post')
      .mockResolvedValue({ data: tokens })
    await loginUser({ ...request, name: 'Jamie' } as typeof request)
    expect(post).toHaveBeenCalledExactlyOnceWith('/auth/login', request)
  })
  it('returns the token response', async () => {
    vi.spyOn(axiosClient, 'post').mockResolvedValue({ data: tokens })
    expect(await loginUser(request)).toEqual(tokens)
  })
})
