export type HomeMode = 'requestor' | 'courier'

const storageKey = 'foc.homeMode'

export const getHomeMode = () => {
  try {
    if (sessionStorage.getItem(storageKey) === 'courier') {
      return 'courier' as const
    }
  } catch {}
  return 'requestor' as const
}

export const rememberHomeMode = (mode: HomeMode) => {
  try {
    sessionStorage.setItem(storageKey, mode)
  } catch {}
}
