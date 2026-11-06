import { useEffect, useState } from 'react'
import { Button } from '@base-ui/react/button'
import { SendHorizontal } from 'lucide-react'
import { useErrandChat } from '../../hooks/useErrandChat'
import { orderErrorMessage } from '../../utils/orderErrors'
import { MAX_MESSAGE_LENGTH } from '../../types/errand.types'
import type { Errand } from '../../types/errand.types'
import styles from './ErrandChat.module.scss'

// A message on its way.
type Outgoing = {
  id: string
  body: string
  failed: boolean
  queuedAt: string
}

// A time label appears before the first message and after a quiet gap.
const GAP_MS = 5 * 60_000

const timeOnly = new Intl.DateTimeFormat('en-SG', {
  timeStyle: 'short',
  timeZone: 'Asia/Singapore',
})
const dayAndTime = new Intl.DateTimeFormat('en-SG', {
  day: 'numeric',
  month: 'short',
  hour: 'numeric',
  minute: '2-digit',
  timeZone: 'Asia/Singapore',
})
const sgDay = (time: Date) =>
  time.toLocaleDateString('en-SG', { timeZone: 'Asia/Singapore' })
export const timeLabel = (sentAt: string, now = new Date()) => {
  const time = new Date(sentAt)
  if (sgDay(time) === sgDay(now)) return timeOnly.format(time)
  const yesterday = new Date(now.getTime() - 24 * 60 * 60_000)
  if (sgDay(time) === sgDay(yesterday))
    return `Yesterday ${timeOnly.format(time)}`
  return dayAndTime.format(time)
}
// Chat between the requester and the courier, open while the errand is in
// progress and read-only once the requester confirms receipt.
export const ErrandChat = ({ order }: { order: Errand }) => {
  // The message list
  const [list, setList] = useState<HTMLOListElement | null>(null)
  const { chat, send, userId, participant, readable, open, reconnecting } =
    useErrandChat(order)
  const [text, setText] = useState('')
  const [outgoing, setOutgoing] = useState<Array<Outgoing>>([])

  const confirmed = chat.data?.messages ?? []
  const sent = new Set(confirmed.map((message) => message.id))
  const waiting = outgoing.filter((message) => !sent.has(message.id))
  const count = confirmed.length + waiting.length

  // Keep the newest message at bottom
  useEffect(() => {
    if (list) list.scrollTop = list.scrollHeight
  }, [list, count])
  if (!order.courier || !readable) return null
  const isRequester = order.requester.id === userId
  const other = isRequester ? order.courier : order.requester
  const otherName =
    other.name ?? (isRequester ? 'your Courier' : 'the Requester')
  const nameOf = (senderId: string) =>
    senderId === order.requester.id
      ? (order.requester.name ?? 'Requester')
      : (order.courier?.name ?? 'Courier')

  const update = (id: string, change: Partial<Outgoing>) =>
    setOutgoing((all) =>
      all.map((item) => (item.id === id ? { ...item, ...change } : item)),
    )
  const deliver = async (message: Outgoing) => {
    update(message.id, { failed: false })
    try {
      await send.mutateAsync({ id: message.id, body: message.body })
    } catch {
      // Kept with a Retry button; resending the same id cannot duplicate it.
      update(message.id, { failed: true })
    }
  }
  const queue = (message: Omit<Outgoing, 'failed' | 'queuedAt'>) => {
    const full = {
      ...message,
      failed: false,
      queuedAt: new Date().toISOString(),
    }
    setOutgoing((all) => [...all, full])
    void deliver(full)
  }
  const submit = () => {
    const body = text.trim()
    if (!body || body.length > MAX_MESSAGE_LENGTH) return
    setText('')
    queue({ id: crypto.randomUUID(), body })
  }
  // One list in time order, with a time label wherever a gap starts
  const rows = [
    ...confirmed.map((message) => ({
      ...message,
      at: message.sentAt,
      pending: null,
    })),
    ...waiting.map((message) => ({
      id: message.id,
      senderId: userId!,
      body: message.body,
      at: message.queuedAt,
      pending: message,
    })),
  ]

  return (
    <section className={styles.panel} aria-label="Chat">
      <h2>
        {participant
          ? `Chat with ${otherName}`
          : `Chat between ${nameOf(order.requester.id)} and ${nameOf(order.courier.id)}`}
      </h2>
      {reconnecting && (
        <p className={styles.notice} role="status">
          Reconnecting… new messages may take a moment to appear.
        </p>
      )}
      {chat.isError && !chat.data ? (
        <p role="alert" className={styles.note}>
          Messages could not be loaded.{' '}
          <button
            type="button"
            className={styles.link}
            onClick={() => void chat.refetch()}
          >
            Try again
          </button>
        </p>
      ) : chat.isPending ? (
        <p className={styles.note}>Loading messages…</p>
      ) : (
        <ol ref={setList} className={styles.messages} aria-label="Messages">
          {!count && (
            <li className={styles.empty}>
              {participant && open
                ? `No messages yet. Say hi to ${otherName}.`
                : 'No messages.'}
            </li>
          )}
          {rows.map((row, index) => {
            const previous = index > 0 ? rows[index - 1] : undefined
            const showTime =
              !previous ||
              Date.parse(row.at) - Date.parse(previous.at) >= GAP_MS
            const mine = row.senderId === userId
            const newSender =
              !previous || previous.senderId !== row.senderId || showTime
            return (
              <li key={row.id} className={styles.row}>
                {showTime && (
                  <time className={styles.separator} dateTime={row.at}>
                    {timeLabel(row.at)}
                  </time>
                )}
                <div
                  className={styles.message}
                  data-mine={mine}
                  data-failed={row.pending?.failed ?? false}
                >
                  {!mine && newSender && (
                    <span className={styles.sender}>
                      {nameOf(row.senderId)}
                    </span>
                  )}
                  <p className={styles.bubble}>{row.body}</p>
                  {row.pending ? (
                    row.pending.failed ? (
                      <span className={styles.status} role="alert">
                        Not sent.{' '}
                        <button
                          type="button"
                          className={styles.link}
                          onClick={() => void deliver(row.pending)}
                        >
                          Retry
                        </button>
                      </span>
                    ) : (
                      <span className={styles.status}>Sending…</span>
                    )
                  ) : null}
                </div>
              </li>
            )
          })}
        </ol>
      )}

      {participant && open ? (
        <form
          className={styles.composer}
          onSubmit={(event) => {
            event.preventDefault()
            submit()
          }}
        >
          <textarea
            aria-label="Message"
            placeholder={`Message ${otherName}`}
            rows={2}
            maxLength={MAX_MESSAGE_LENGTH}
            value={text}
            onChange={(event) => setText(event.target.value)}
            onKeyDown={(event) => {
              // Enter sends, Shift+Enter starts a new line.
              // Ignore Enter while an input method like pinyin is still composing
              if (
                event.key === 'Enter' &&
                !event.shiftKey &&
                !event.nativeEvent.isComposing
              ) {
                event.preventDefault()
                submit()
              }
            }}
          />
          <Button
            type="submit"
            className={styles.send}
            aria-label="Send message"
            disabled={!text.trim()}
          >
            <SendHorizontal size={18} aria-hidden="true" />
          </Button>
        </form>
      ) : (
        <p className={styles.note}>
          {participant
            ? 'Errand ended, this chat is closed for view only'
            : 'Read-only.'}
        </p>
      )}
      {send.error && waiting.some((message) => message.failed) && (
        <p role="alert" className={styles.error}>
          {orderErrorMessage(send.error)}
        </p>
      )}
    </section>
  )
}
