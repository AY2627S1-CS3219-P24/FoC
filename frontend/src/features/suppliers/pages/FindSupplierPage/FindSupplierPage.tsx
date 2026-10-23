import { useState } from 'react'
import { Link, useNavigate, useSearch } from '@tanstack/react-router'
import { Button } from '@base-ui/react/button'
import { CategoryTiles } from '../../components/CategoryTiles/CategoryTiles'
import { SupplierSlideOver } from '../../components/SupplierSlideOver/SupplierSlideOver'
import { useSuppliers } from '../../hooks/useSuppliers'
import { SUPPLIER_CATEGORIES } from '../../types/supplier.types'
import type { Supplier, SupplierCategory } from '../../types/supplier.types'
import { categoryLabels, formatLocation } from '../../utils/supplierFormat'
import { describeOpenStatus } from '../../utils/openingHours'
import styles from './FindSupplierPage.module.scss'

export const FindSupplierPage = () => {
  const { category } = useSearch({ from: '/authenticated/user/request' })
  const navigate = useNavigate()
  const suppliers = useSuppliers(category ? { category: [category] } : {})
  const [selected, setSelected] = useState<Supplier | null>(null)
  const selectCategory = (next: SupplierCategory) => {
    void navigate({
      to: '/request',
      search: { category: category === next ? undefined : next },
    })
  }

  return (
    <section className={styles.page}>
      <h1>Find a supplier</h1>
      <CategoryTiles />
      <div className={styles.chips} role="group" aria-label="Supplier categories">
        {SUPPLIER_CATEGORIES.map((item) => (
          <Button
            key={item}
            className={styles.chip}
            aria-pressed={category === item}
            onClick={() => selectCategory(item)}
          >
            {categoryLabels[item]}
          </Button>
        ))}
      </div>
      {category && (
        <Link className={styles.categoryLink} to="/locations" search={{ category }}>
          See all {categoryLabels[category]}
        </Link>
      )}

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

      <div className={styles.footer}>
        <Link to="/map">Explore campus map</Link>
        <Link to="/request">Browse all suppliers</Link>
      </div>
      <SupplierSlideOver
        supplier={selected}
        from="request"
        category={category}
        onClose={() => setSelected(null)}
      />
    </section>
  )
}
