import { afterEach, expect, it, vi } from 'vitest'
import { axiosClient } from '#/lib/axiosClient'
import { logoutUser } from './logoutUser.api'

afterEach(() => vi.restoreAllMocks())

it('posts logout with a bounded timeout and no token payload', async () => {
  const post = vi.spyOn(axiosClient, 'post').mockResolvedValue({ status: 200 })
  await expect(logoutUser()).resolves.toBeUndefined()
  expect(post).toHaveBeenCalledExactlyOnceWith('/auth/logout', undefined, {
    timeout: 10_000,
  })
})
