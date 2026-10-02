import { useState } from 'react'
import { Link } from '@tanstack/react-router'
import { Button } from '@base-ui/react/button'
import { Dialog } from '@base-ui/react/dialog'
import { SUPPLIER_CATEGORIES } from '#/features/suppliers/types/supplier.types'
import { categoryLabels } from '#/features/suppliers/utils/supplierFormat'
import { categoryImages, recentErrands } from './requesterHome.data'
import styles from './RequesterHomePage.module.scss'
import { RoleSwitcher } from '#/features/orders/components/RoleSwitcher/RoleSwitcher'

export const RequesterHomePage = () => {
  const [preview, setPreview] = useState<{
    title: string
    description: string
  } | null>(null)

  return (
    <div className={styles.page}>
      <RoleSwitcher activeMode="requestor" />
      <h1>What do you need?</h1>
      <ul className={styles.categories} aria-label="Location categories">
        {SUPPLIER_CATEGORIES.map((category) => (
          <li key={category}>
            <Link
              to="/locations"
              search={{ category }}
              className={styles.category}
            >
              <span className={styles.tile} aria-hidden="true">
                <img src={categoryImages[category]} alt="" />
              </span>
              <span>{categoryLabels[category]}</span>
            </Link>
          </li>
        ))}
      </ul>

      <section className={styles.section} aria-labelledby="recent-heading">
        <h2 id="recent-heading">Recent errands</h2>
        <ul className={styles.cards}>
          {recentErrands.map((errand) => (
            <li key={errand.id}>
              <Button
                className={styles.card}
                onClick={() =>
                  setPreview({
                    title: `${errand.pickup} → ${errand.destination}`,
                    description: `${errand.label}: ${errand.description} This is a sample errand.`,
                  })
                }
              >
                <span>
                  <span className={styles.cardTitle}>
                    {errand.pickup} → {errand.destination}
                  </span>{' '}
                  <span className={styles.status}>{errand.label}</span>
                </span>
                <span className={styles.arrow} aria-hidden="true">
                  ›
                </span>
              </Button>
            </li>
          ))}
        </ul>
      </section>

      <Dialog.Root
        open={preview !== null}
        onOpenChange={(open) => {
          if (!open) setPreview(null)
        }}
      >
        <Dialog.Portal>
          <Dialog.Backdrop className={styles.backdrop} />
          <Dialog.Popup className={styles.dialog}>
            <Dialog.Title>{preview?.title}</Dialog.Title>
            <Dialog.Description>{preview?.description}</Dialog.Description>
            <Dialog.Close className={styles.create}>Close</Dialog.Close>
          </Dialog.Popup>
        </Dialog.Portal>
      </Dialog.Root>
    </div>
  )
}
