import { useState } from 'react'
import { Button } from '@base-ui/react/button'
import { Dialog } from '@base-ui/react/dialog'
import styles from '../CourierHomePage/CourierHomePage.module.scss'
import {
  useCourierErrands,
  useErrand,
  usePickUpErrand,
} from '../../hooks/useErrands'
import { getOrderErrorMessage } from '../../utils/getOrderErrorMessage'
import type { CreditState, ErrandStatus } from '../../types/errand.types'

const statusLabels: Record<ErrandStatus, string> = {
  CREATING: 'Creating',
  CREATE_FAILED: 'Creation failed',
  OPEN: 'Open',
  ACCEPTED: 'Accepted',
  PICKED_UP: 'Picked up',
  COMPLETED: 'Completed',
  CANCELLED: 'Cancelled',
  EXPIRED: 'Expired',
}

const paymentLabels: Record<CreditState, string> = {
  RESERVED: 'Credits reserved',
  SETTLEMENT_PENDING: 'Payment pending',
  SETTLED: 'Paid',
  RELEASE_PENDING: 'Credit release pending',
  RELEASED: 'Credits released',
}

export const MyErrandsPage = () => {
  const [page, setPage] = useState(0)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [message, setMessage] = useState('')
  const errands = useCourierErrands(page)
  const detail = useErrand(selectedId)
  const pickup = usePickUpErrand()
  const selected = detail.data

  return (
    <section aria-labelledby="my-errands-heading" className={styles.page}>
      <div className={styles.heading}>
        <div>
          <h1 id="my-errands-heading">My Errands</h1>
          <p>Errands assigned to you as a courier</p>
        </div>
      </div>
      {message && <p role="status">{message}</p>}
      {errands.isPending ? (
        <p role="status">Loading errands...</p>
      ) : errands.isError ? (
        <div role="alert" className={styles.empty}>
          <p>{getOrderErrorMessage(errands.error)}</p>
          <Button onClick={() => void errands.refetch()}>Try again</Button>
        </div>
      ) : (
        <>
          <ul className={styles.list}>
            {errands.data.items.map((errand) => (
              <li key={errand.id}>
                <Button
                  className={styles.card}
                  onClick={() => {
                    pickup.reset()
                    setMessage('')
                    setSelectedId(errand.id)
                  }}
                  aria-label={`View errand from ${errand.pickup.name} to ${errand.deliveryLocation}`}
                >
                  <div className={styles.details}>
                    <span className={styles.openBadge}>
                      {statusLabels[errand.status]}
                    </span>
                    <h2>
                      {errand.pickup.name} → {errand.deliveryLocation}
                    </h2>
                    <p>{errand.description}</p>
                  </div>
                  <span className={styles.credits}>
                    {errand.creditReward} credits
                  </span>
                  <span className={styles.action}>
                    <span className={styles.chevron} aria-hidden="true">
                      ›
                    </span>
                  </span>
                </Button>
              </li>
            ))}
          </ul>
          {errands.data.items.length === 0 && (
            <p role="status" className={styles.empty}>
              No assigned errands on this page.
            </p>
          )}
        </>
      )}
      <nav aria-label="Errand pages" className={styles.actions}>
        <Button
          className={styles.close}
          disabled={page === 0 || errands.isFetching}
          onClick={() => setPage(page - 1)}
        >
          Previous
        </Button>
        <span>Page {page + 1}</span>
        <Button
          className={styles.close}
          disabled={
            errands.isFetching ||
            errands.isError ||
            !errands.data ||
            (page + 1) * errands.data.size >= errands.data.total
          }
          onClick={() => setPage(page + 1)}
        >
          Next
        </Button>
      </nav>
      <Dialog.Root
        open={selectedId !== null}
        onOpenChange={(open) => {
          if (!open && !pickup.isPending) setSelectedId(null)
        }}
      >
        <Dialog.Portal>
          <Dialog.Backdrop className={styles.backdrop} />
          <Dialog.Popup className={styles.dialog}>
            <Dialog.Title>Errand details</Dialog.Title>
            {detail.isPending ? (
              <p role="status">Loading details...</p>
            ) : detail.isError ? (
              <div role="alert">
                <p>{getOrderErrorMessage(detail.error)}</p>
                <Button onClick={() => void detail.refetch()}>Try again</Button>
              </div>
            ) : selected ? (
              <>
                <h3>
                  {selected.pickup.name} → {selected.deliveryLocation}
                </h3>
                <Dialog.Description>{selected.description}</Dialog.Description>
                <p>Pickup: {selected.pickup.address}</p>
                {selected.requester.name && (
                  <p>Requester: {selected.requester.name}</p>
                )}
                {selected.notes && <p>Notes: {selected.notes}</p>}
                <p>{selected.creditReward} credits</p>
                <p>Status: {statusLabels[selected.status]}</p>
                <p>
                  Payment:{' '}
                  {selected.creditState
                    ? paymentLabels[selected.creditState]
                    : 'Unavailable'}
                </p>
              </>
            ) : null}
            {pickup.isError && (
              <p role="alert">{getOrderErrorMessage(pickup.error)}</p>
            )}
            <div className={styles.actions}>
              {selected?.status === 'ACCEPTED' && (
                <Button
                  className={styles.close}
                  disabled={
                    detail.isError || detail.isFetching || pickup.isPending
                  }
                  onClick={() => {
                    if (!selectedId) return
                    pickup.mutate(selectedId, {
                      onSuccess: () => {
                        setSelectedId(null)
                        setMessage('Pickup confirmed.')
                      },
                    })
                  }}
                >
                  {pickup.isPending ? 'Confirming...' : 'Confirm pickup'}
                </Button>
              )}
              <Dialog.Close
                className={styles.close}
                disabled={pickup.isPending}
              >
                Close
              </Dialog.Close>
            </div>
          </Dialog.Popup>
        </Dialog.Portal>
      </Dialog.Root>
    </section>
  )
}
