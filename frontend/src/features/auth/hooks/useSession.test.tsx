import { act, renderHook, cleanup } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import { replaceSession, setAccessToken } from '../lib/accessTokenStore'
import { useSession } from './useSession'

const token = (sub: string, roles = ['USER']) =>
  `header.${btoa(JSON.stringify({ sub, roles }))}.signature`
afterEach(() => {
  cleanup()
  replaceSession(null)
})

it('provides identity without Profile, preserves a login on refresh and changes it on logout or login', () => {
  replaceSession(token('A'))
  const { result } = renderHook(() => useSession())
  const version = result.current.version
  expect(result.current.userId).toBe('A')
  act(() => setAccessToken(token('A', ['USER', 'ADMIN'])))
  expect(result.current).toEqual({
    userId: 'A',
    roles: ['USER', 'ADMIN'],
    version,
  })
  act(() => replaceSession(null))
  expect(result.current.userId).toBeUndefined()
  expect(result.current.version).toBeGreaterThan(version)
  act(() => replaceSession(token('B')))
  expect(result.current.userId).toBe('B')
})
