import { useState } from 'react'
import { Link, useSearch } from '@tanstack/react-router'
import { Button } from '@base-ui/react/button'
import { Dialog } from '@base-ui/react/dialog'
import { useSuppliers } from '../../hooks/useSuppliers'
import type { Supplier } from '../../types/supplier.types'
import { categoryLabels, formatLocation } from '../../utils/supplierFormat'
import { formatWeeklyHours } from '../../utils/openingHours'
import {
  parseSupplierListSearch,
  toSupplierFilters,
} from '../../utils/supplierFilters'
import styles from './SupplierCategoryPage.module.scss'

export const SupplierCategoryPage = () => {
  const search = useSearch({ strict: false })
  const filters = toSupplierFilters(parseSupplierListSearch(search))
  const category = filters.category?.[0]
  const suppliers = useSuppliers(filters)
  const [selectedSupplier, setSelectedSupplier] = useState<Supplier | null>(
    null,
  )

  if (!category) return null

  const label = categoryLabels[category]

  return (
    <section className={styles.page}>
      <Link to="/app" className={styles.back}>
        ← Back to categories
      </Link>
      <header className={styles.header}>
        <div>
          <p className={styles.eyebrow}>Locations</p>
          <h1>{label}</h1>
        </div>
        {suppliers.isFetching && suppliers.isSuccess ? (
          <span className={styles.updating} role="status">
            Updating…
          </span>
        ) : null}
      </header>

      {suppliers.isPending ? (
        <p className={styles.feedback} role="status">
          Loading {label.toLowerCase()} suppliers…
        </p>
      ) : null}
      {suppliers.isError ? (
        <p className={`${styles.feedback} ${styles.error}`} role="alert">
          Unable to load suppliers. Please try again.
        </p>
      ) : null}
      {suppliers.isSuccess && suppliers.data.length === 0 ? (
        <p className={styles.feedback}>
          No {label.toLowerCase()} suppliers found.
        </p>
      ) : null}
      {suppliers.isSuccess && suppliers.data.length > 0 ? (
        <ul className={styles.grid}>
          {suppliers.data.map((supplier) => {
            const hours = formatWeeklyHours(supplier.openingHours)
            return (
              <li key={supplier.id}>
                <Button
                  className={styles.card}
                  onClick={() => setSelectedSupplier(supplier)}
                >
                  <span className={styles.image} aria-hidden="true">
                    {supplier.imageUrl ? (
                      <img src={supplier.imageUrl} alt="" />
                    ) : (
                      supplier.name.slice(0, 1).toUpperCase()
                    )}
                  </span>
                  <span className={styles.cardBody}>
                    <span className={styles.name}>{supplier.name}</span>
                    <span className={styles.location}>
                      {formatLocation(supplier)}
                    </span>
                    <span className={styles.hours}>{hours[0]}</span>
                  </span>
                  <span className={styles.arrow} aria-hidden="true">
                    ›
                  </span>
                </Button>
              </li>
            )
          })}
        </ul>
      ) : null}

      <Dialog.Root
        open={selectedSupplier !== null}
        onOpenChange={(open) => {
          if (!open) setSelectedSupplier(null)
        }}
      >
        <Dialog.Portal>
          <Dialog.Backdrop className={styles.backdrop} />
          <Dialog.Popup className={styles.dialog}>
            <Dialog.Title>{selectedSupplier?.name}</Dialog.Title>
            <Dialog.Description>
              {selectedSupplier
                ? `${formatLocation(selectedSupplier)}. ${formatWeeklyHours(selectedSupplier.openingHours).join('; ')}.`
                : ''}
            </Dialog.Description>
            <Dialog.Close className={styles.close}>Close</Dialog.Close>
          </Dialog.Popup>
        </Dialog.Portal>
      </Dialog.Root>
    </section>
  )
}
