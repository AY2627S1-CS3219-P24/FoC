let session = { accessToken: null as string | null, version: 0 }
const listeners = new Set<() => void>()

export const getAccessToken = () => session.accessToken
export const getSessionVersion = () => session.version
export const getSessionSnapshot = () => session
export const subscribeSession = (listener: () => void) => {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

export const setAccessToken = (token: string | null) => {
  if (session.accessToken === token) return
  session = { ...session, accessToken: token }
  listeners.forEach((listener) => listener())
}

// Explicit login/logout ends the previous session. Token rotation preserves it.
export const replaceSession = (token: string | null) => {
  session = { accessToken: token, version: session.version + 1 }
  listeners.forEach((listener) => listener())
}
