import { useRef, useState } from 'react'
import { Button } from '@base-ui/react/button'
import { Dialog } from '@base-ui/react/dialog'
import { availableOrderActions } from '../../utils/orderPermissions'
import { useOrderActions } from '../../hooks/useOrderActions'
import type { Errand, OrderAction, OrderUser } from '../../types/errand.types'
import styles from '../../styles/order.module.scss'
import dialog from './OrderActions.module.scss'

const labels: Record<OrderAction, string> = {
  accept: 'Accept errand',
  pickup: "I've collected it",
  complete: 'Confirm received',
  cancel: 'Cancel request',
}
export const OrderActions = ({
  order,
  user,
  now,
}: {
  order: Errand
  user: OrderUser
  now: number
}) => {
  const { action } = useOrderActions(user)
  const [confirm, setConfirm] = useState<OrderAction | null>(null)
  const [message, setMessage] = useState('')
  const lock = useRef(false)
  const allowed = availableOrderActions(order, user.id, now)
  const submit = async (operation: OrderAction) => {
    if (lock.current) return
    lock.current = true
    setMessage('')
    try {
      await action.mutateAsync({ id: order.id, action: operation })
      setConfirm(null)
      setMessage('Preview updated. No real order has been changed.')
    } catch {
      /* Mutation error is displayed below and input stays available. */
    } finally {
      lock.current = false
    }
  }
  if (
    !allowed.length &&
    !confirm &&
    !message &&
    !action.error &&
    order.status !== 'PICKED_UP' &&
    order.status !== 'ACCEPTED'
  )
    return null
  return (
    <section className={styles.feedback} aria-label="Order actions">
      {order.status === 'ACCEPTED' && order.requester.id === user.id && (
        <p className={styles.muted}>
          {order.courier?.name} is collecting your item.
        </p>
      )}
      {order.status === 'PICKED_UP' && (
        <p className={styles.muted}>
          {order.requester.id === user.id
            ? 'Confirm only after you receive your item. Credit settlement starts after confirmation.'
            : `${order.requester.name} confirms receipt after delivery.`}
        </p>
      )}
      {message && <p role="status">{message}</p>}
      {action.error && !confirm && (
        <p role="alert" className={styles.error}>
          {action.error.message}
        </p>
      )}
      <div className={styles.actions}>
        {allowed.map((operation) => (
          <Button
            key={operation}
            className={
              operation === 'cancel' ? styles.secondary : styles.button
            }
            disabled={action.isPending}
            onClick={() => {
              action.reset()
              if (operation === 'cancel' || operation === 'complete')
                setConfirm(operation)
              else void submit(operation)
            }}
          >
            {action.isPending ? 'Saving…' : labels[operation]}
          </Button>
        ))}
      </div>
      <Dialog.Root
        open={confirm !== null}
        onOpenChange={(open) => {
          if (!open && !action.isPending) {
            setConfirm(null)
            action.reset()
          }
        }}
      >
        <Dialog.Portal>
          <Dialog.Backdrop className={dialog.backdrop} />
          <Dialog.Popup className={dialog.popup}>
            <Dialog.Title>
              {confirm === 'cancel'
                ? 'Cancel this request?'
                : 'Have you received your item?'}
            </Dialog.Title>
            <Dialog.Description>
              {confirm === 'cancel'
                ? `Your ${order.reward} reserved credits will be released after cancellation is processed.`
                : `Confirm only after receiving your item. This completes the errand and starts settlement of ${order.reward} credits to ${order.courier?.name}.`}
            </Dialog.Description>
            {action.error && (
              <p role="alert" className={styles.error}>
                {action.error.message}
              </p>
            )}
            {confirm && !allowed.includes(confirm) && (
              <p role="alert">
                This action is no longer available. Close this dialog to view
                the latest order.
              </p>
            )}
            <div className={styles.actions}>
              <Dialog.Close
                className={styles.secondary}
                disabled={action.isPending}
              >
                Go back
              </Dialog.Close>
              <Button
                className={styles.button}
                disabled={
                  action.isPending || !confirm || !allowed.includes(confirm)
                }
                onClick={() => {
                  if (confirm) void submit(confirm)
                }}
              >
                {action.isPending
                  ? 'Saving…'
                  : confirm === 'cancel'
                    ? 'Confirm cancellation'
                    : 'Confirm receipt'}
              </Button>
            </div>
          </Dialog.Popup>
        </Dialog.Portal>
      </Dialog.Root>
    </section>
  )
}
