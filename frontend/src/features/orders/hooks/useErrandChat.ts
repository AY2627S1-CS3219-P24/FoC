import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { QueryClient } from '@tanstack/react-query'
import { useSession } from '#/features/auth/hooks/useSession'
import { connectRealtime } from '#/lib/realtime'
import { getChat, sendMessage } from '../api/orders.api'
import { errandQueryKeys } from '../constants/errandQueryKeys'
import { chatIsOpen } from '../utils/orderPermissions'
import type { Chat, Errand, ErrandMessage } from '../types/errand.types'

// add messages to the cached conversation and oldest first
export const addMessages = (
  client: QueryClient,
  key: ReadonlyArray<unknown>,
  incoming: Array<ErrandMessage>,
) =>
  client.setQueryData<Chat>(key, (chat = { messages: [] }) => {
    const byId = new Map(chat.messages.map((message) => [message.id, message]))
    incoming.forEach((message) => byId.set(message.id, message))
    const messages = [...byId.values()].sort(
      (a, b) =>
        Date.parse(a.sentAt) - Date.parse(b.sentAt) || a.id.localeCompare(b.id),
    )
    return { ...chat, messages }
  })

export const useErrandChat = (order: Errand) => {
  const { userId, roles } = useSession()
  const client = useQueryClient()
  const participant =
    !!userId &&
    !!order.courier &&
    (order.requester.id === userId || order.courier.id === userId)
  const readable = participant || roles.includes('ADMIN')
  const open = chatIsOpen(order)
  // pushed messages arrive through the realtime connection while it is open
  const live = participant && open
  // null while connecting, false when it has failed for a few seconds
  const [connected, setConnected] = useState<boolean | null>(null)

  const chat = useQuery({
    queryKey: errandQueryKeys.messages(userId, order.id),
    queryFn: ({ signal }) => getChat(order.id, signal),
    enabled: readable && !!order.courier,
    refetchInterval: open ? 30_000 : false,
    refetchOnWindowFocus: 'always',
    retry: false,
  })

  useEffect(() => {
    if (!live) return
    const key = errandQueryKeys.messages(userId, order.id)
    const controller = new AbortController()
    const socket = connectRealtime(controller.signal)
    const refetch = () =>
      void client.invalidateQueries({ queryKey: key, exact: true })
    const onMessage = (message: ErrandMessage) => {
      if (message.errandId === order.id) addMessages(client, key, [message])
    }
    // after re-connect or connect, catch up anything sent offline
    const onConnect = () => {
      setConnected(true)
      refetch()
    }
    const onDisconnect = () => setConnected(false)
    const slow = setTimeout(
      () => setConnected((now) => (now === null ? false : now)),
      4_000,
    )
    socket.on('errand.message', onMessage)
    socket.on('connect', onConnect)
    socket.on('disconnect', onDisconnect)
    return () => {
      clearTimeout(slow)
      socket.off('errand.message', onMessage)
      socket.off('connect', onConnect)
      socket.off('disconnect', onDisconnect)
      controller.abort()
      setConnected(null)
    }
  }, [live, client, userId, order.id])

  const key = errandQueryKeys.messages(userId, order.id)
  const send = useMutation({
    networkMode: 'always',
    retry: false,
    mutationFn: (message: { id: string; body: string }) =>
      sendMessage(order.id, message),
    onSuccess: (message) => addMessages(client, key, [message]),
  })
  return {
    chat,
    send,
    userId,
    participant,
    readable,
    open,
    reconnecting: live && connected === false,
  }
}
