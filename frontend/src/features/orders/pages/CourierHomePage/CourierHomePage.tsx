import { useState } from 'react'
import { Button } from '@base-ui/react/button'
import { Dialog } from '@base-ui/react/dialog'
import { sampleErrands } from './courierHome.data'
import styles from './CourierHomePage.module.scss'
import { RoleSwitcher } from '#/features/orders/components/RoleSwitcher/RoleSwitcher'

export const CourierHomePage = () => {
  const [search, setSearch] = useState('')
  const [selected, setSelected] = useState<
    (typeof sampleErrands)[number] | null
  >(null)
  const query = search.trim().toLowerCase()
  const errands = sampleErrands.filter((errand) =>
    `${errand.pickup} ${errand.destination} ${errand.description}`
      .toLowerCase()
      .includes(query),
  )

  return (
    <section aria-labelledby="courier-heading" className={styles.page}>
      <RoleSwitcher activeMode={'courier'} />
      <div className={styles.heading}>
        <div>
          <h1 id="courier-heading">Find an errand</h1>
          <p>Only active open errands from other requesters are actionable</p>
        </div>
        <label className={styles.search}>
          <span className={styles.srOnly}>Search errands</span>
          <input
            type="search"
            placeholder="Type to search..."
            value={search}
            onChange={(event) => setSearch(event.target.value)}
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
      <p className={styles.sampleNote}>
        Sample errands — expiry times and credits are illustrative.
      </p>
      <ul className={styles.list}>
        {errands.map((errand) => {
          const content = (
            <>
              <div className={styles.details}>
                <span
                  className={
                    errand.isOwnRequest ? styles.ownBadge : styles.openBadge
                  }
                >
                  {errand.isOwnRequest
                    ? 'YOUR REQUEST'
                    : `OPEN - expires in ${errand.expiresInMinutes} mins`}
                </span>
                <h2>
                  {errand.pickup} → {errand.destination}
                </h2>
                <p>{errand.description}</p>
              </div>
              <span className={styles.credits}>
                {errand.credits} credits{errand.isOwnRequest ? ' reserved' : ''}
              </span>
              <span className={styles.action}>
                {errand.isOwnRequest ? (
                  'Not eligible'
                ) : (
                  <span className={styles.chevron} aria-hidden="true">
                    ›
                  </span>
                )}
              </span>
            </>
          )
          return (
            <li key={errand.id}>
              {errand.isOwnRequest ? (
                <div className={styles.card}>{content}</div>
              ) : (
                <Button
                  className={styles.card}
                  onClick={() => setSelected(errand)}
                  aria-label={`View errand from ${errand.pickup} to ${errand.destination}`}
                >
                  {content}
                </Button>
              )}
            </li>
          )
        })}
      </ul>
      {errands.length === 0 && (
        <p role="status" className={styles.empty}>
          No errands match your search. Try another location or description.
        </p>
      )}
      <Dialog.Root
        open={selected !== null}
        onOpenChange={(open) => {
          if (!open) setSelected(null)
        }}
      >
        <Dialog.Portal>
          <Dialog.Backdrop className={styles.backdrop} />
          <Dialog.Popup className={styles.dialog}>
            <Dialog.Title>
              {selected?.pickup} → {selected?.destination}
            </Dialog.Title>
            <Dialog.Description>{selected?.description}</Dialog.Description>
            <p>{selected?.credits} credits</p>
            <p>
              This is a sample errand. Accepting errands will be available when
              the order service is connected.
            </p>
            <Dialog.Close className={styles.close}>Close</Dialog.Close>
          </Dialog.Popup>
        </Dialog.Portal>
      </Dialog.Root>
    </section>
  )
}
