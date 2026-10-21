import { io } from 'socket.io-client'
import type { Socket } from 'socket.io-client'
import { getAccessToken } from '#/features/auth/lib/accessTokenStore'
import { refreshAccessToken } from '#/features/auth/lib/refreshAccessToken'

export const connectRealtime = (): Socket => {
  const socket = io({ auth: { token: getAccessToken() } })
  socket.on('connect_error', () => {
    void refreshAccessToken().then((token) => {
      if (!token) return
      socket.auth = { token }
      socket.connect()
    })
  })
  return socket
}
