import { useState } from 'react'
import { Button } from '@base-ui/react/button'
import { Dialog } from '@base-ui/react/dialog'
import styles from './CourierHomePage.module.scss'
import { RoleSwitcher } from '#/features/orders/components/RoleSwitcher/RoleSwitcher'
import {
  useAcceptErrand,
  useAvailableErrands,
  useErrand,
} from '#/features/orders/hooks/useErrands'
import { getOrderErrorMessage } from '#/features/orders/utils/getOrderErrorMessage'

export const CourierHomePage = () => {
  const [page, setPage] = useState(0)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [message, setMessage] = useState('')
  const available = useAvailableErrands(page)
  const detail = useErrand(selectedId)
  const acceptance = useAcceptErrand()
  const selected = detail.data
  const canAccept =
    selected?.status === 'OPEN' &&
    new Date(selected.expiresAt).getTime() > Date.now()

  return (
    <section aria-labelledby="courier-heading" className={styles.page}>
      <RoleSwitcher activeMode="courier" />
      <div className={styles.heading}>
        <div>
          <h1 id="courier-heading">Find an errand</h1>
          <p>Only active open errands from other requesters are actionable</p>
        </div>
        <label className={styles.search}>
          <span className={styles.srOnly}>Search errands</span>
          <input
            type="search"
            placeholder="Search unavailable"
            title="Errand search is not available yet"
            disabled
          />
          <svg
            width="18"
            height="18"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            aria-hidden="true"
          >
            <circle cx="10" cy="10" r="6" />
            <path d="m15 15 5 5" />
          </svg>
        </label>
      </div>
      {message && <p role="status">{message}</p>}
      {available.isPending ? (
        <p role="status">Loading errands...</p>
      ) : available.isError ? (
        <div role="alert" className={styles.empty}>
          <p>{getOrderErrorMessage(available.error)}</p>
          <Button onClick={() => void available.refetch()}>Try again</Button>
        </div>
      ) : (
        <>
          <ul className={styles.list}>
            {available.data.items.map((errand) => (
              <li key={errand.id}>
                <Button
                  className={styles.card}
                  onClick={() => {
                    acceptance.reset()
                    setMessage('')
                    setSelectedId(errand.id)
                  }}
                  aria-label={`View errand from ${errand.pickup.name} to ${errand.deliveryLocation}`}
                >
                  <div className={styles.details}>
                    <span className={styles.openBadge}>
                      OPEN — expires{' '}
                      {new Date(errand.expiresAt).toLocaleString()}
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
          {available.data.items.length === 0 && (
            <p role="status" className={styles.empty}>
              No available errands on this page.
            </p>
          )}
        </>
      )}
      <nav aria-label="Errand pages" className={styles.actions}>
        <Button
          className={styles.close}
          disabled={page === 0 || available.isFetching}
          onClick={() => setPage(page - 1)}
        >
          Previous
        </Button>
        <span>Page {page + 1}</span>
        <Button
          className={styles.close}
          disabled={
            available.isFetching ||
            available.isError ||
            !available.data ||
            (page + 1) * available.data.size >= available.data.total
          }
          onClick={() => setPage(page + 1)}
        >
          Next
        </Button>
      </nav>
      <Dialog.Root
        open={selectedId !== null}
        onOpenChange={(open) => {
          if (!open && !acceptance.isPending) setSelectedId(null)
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
                <p>Expires {new Date(selected.expiresAt).toLocaleString()}</p>
                {!canAccept && (
                  <p>This errand is no longer open for acceptance.</p>
                )}
              </>
            ) : null}
            {acceptance.isError && (
              <p role="alert">{getOrderErrorMessage(acceptance.error)}</p>
            )}
            <div className={styles.actions}>
              <Button
                className={styles.close}
                disabled={
                  !canAccept ||
                  detail.isError ||
                  detail.isFetching ||
                  acceptance.isPending
                }
                onClick={() => {
                  if (!selectedId) return
                  acceptance.mutate(selectedId, {
                    onSuccess: () => {
                      setSelectedId(null)
                      setMessage('Errand accepted.')
                    },
                  })
                }}
              >
                {acceptance.isPending ? 'Accepting...' : 'Accept errand'}
              </Button>
              <Dialog.Close
                className={styles.close}
                disabled={acceptance.isPending}
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
