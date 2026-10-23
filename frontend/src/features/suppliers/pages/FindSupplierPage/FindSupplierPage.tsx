import { useState } from 'react'
import { Button } from '@base-ui/react/button'
import { CategoryTiles } from '../../components/CategoryTiles/CategoryTiles'
import { SupplierSlideOver } from '../../components/SupplierSlideOver/SupplierSlideOver'
import { useSuppliers } from '../../hooks/useSuppliers'
import type { Supplier } from '../../types/supplier.types'
import { formatLocation } from '../../utils/supplierFormat'
import { describeOpenStatus } from '../../utils/openingHours'
import styles from './FindSupplierPage.module.scss'

export const FindSupplierPage = () => {
  const suppliers = useSuppliers()
  const [selected, setSelected] = useState<Supplier | null>(null)

  return (
    <section className={styles.page}>
      <h1>Find a supplier</h1>
      <CategoryTiles />

      <div className={styles.results}>
        <div className={styles.resultsHeader}>
          <h2>Suppliers</h2>
        </div>
      {suppliers.isPending ? (
        <p className={styles.feedback} role="status">
          Loading suppliers…
        </p>
      ) : null}
      {suppliers.isError ? (
        <p className={`${styles.feedback} ${styles.error}`} role="alert">
          Unable to load suppliers. Please try again.
        </p>
      ) : null}
      {suppliers.isSuccess && suppliers.data.length === 0 ? (
        <p className={styles.feedback}>No suppliers found.</p>
      ) : null}
      {suppliers.isSuccess && suppliers.data.length > 0 ? (
        <ul className={styles.grid}>
          {suppliers.data.map((supplier) => {
            const open = describeOpenStatus(supplier.openingHours, new Date())
            return (
              <li key={supplier.id}>
                <Button
                  className={styles.card}
                  onClick={() => setSelected(supplier)}
                >
                  <span className={styles.image} aria-hidden="true">
                    {supplier.imageUrl ? (
                      <img src={supplier.imageUrl} alt="" />
                    ) : (
                      supplier.name.slice(0, 1).toUpperCase()
                    )}
                  </span>
                  <span className={styles.name}>{supplier.name}</span>
                  <span className={styles.meta}>
                    {formatLocation(supplier)} · {open === 'Closed' ? 'Closed' : 'Open now'}
                  </span>
                </Button>
              </li>
            )
          })}
        </ul>
      ) : null}
      </div>
      <SupplierSlideOver
        supplier={selected}
        from="request"
        onClose={() => setSelected(null)}
      />
    </section>
  )
}
