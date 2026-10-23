import { lazy, Suspense, useMemo, useState } from 'react'
import { ChevronRight } from 'lucide-react'
import { SupplierSlideOver } from '../../components/SupplierSlideOver/SupplierSlideOver'
import { useSuppliers } from '../../hooks/useSuppliers'
import { SUPPLIER_CATEGORIES } from '../../types/supplier.types'
import type { Supplier, SupplierCategory } from '../../types/supplier.types'
import { categoryLabels } from '../../utils/supplierFormat'
import { describeOpenStatus } from '../../utils/openingHours'
import styles from './CampusMapPage.module.scss'

const CampusMap = lazy(() =>
  import('./CampusMap').then((module) => ({ default: module.CampusMap })),
)

const hasCoordinates = (
  supplier: Supplier,
): supplier is Supplier & { latitude: number; longitude: number } =>
  supplier.latitude != null && supplier.longitude != null

type SortMode = 'name' | 'open'
type FilterMode = 'all' | 'open' | SupplierCategory

const matchesQuery = (supplier: Supplier, query: string) => {
  const haystack = [
    supplier.name,
    supplier.building,
    supplier.floor,
    supplier.locationDescription,
    categoryLabels[supplier.category],
  ]
    .filter(Boolean)
    .join(' ')
    .toLowerCase()
  return haystack.includes(query)
}

export const CampusMapPage = () => {
  const suppliers = useSuppliers()
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [query, setQuery] = useState('')
  const [sort, setSort] = useState<SortMode>('name')
  const [filter, setFilter] = useState<FilterMode>('all')
  const now = useMemo(() => new Date(), [])
  const locations = (suppliers.data ?? []).filter((supplier) => supplier.active)
  const visible = locations
    .filter((supplier) => {
      const open = describeOpenStatus(supplier.openingHours, now) !== 'Closed'
      if (filter === 'open' && !open) return false
      if (filter !== 'all' && filter !== 'open' && supplier.category !== filter) {
        return false
      }
      const needle = query.trim().toLowerCase()
      return needle ? matchesQuery(supplier, needle) : true
    })
    .sort((left, right) => {
      if (sort === 'open') {
        const leftOpen =
          describeOpenStatus(left.openingHours, now) === 'Closed' ? 1 : 0
        const rightOpen =
          describeOpenStatus(right.openingHours, now) === 'Closed' ? 1 : 0
        if (leftOpen !== rightOpen) return leftOpen - rightOpen
      }
      return left.name.localeCompare(right.name)
    })
  const mapped = visible.filter(hasCoordinates)
  const selected =
    visible.find((supplier) => supplier.id === selectedId) ?? null

  return (
    <section className={styles.stage} aria-label="Suppliers locations">
      <h1 className={styles.heading}>Suppliers locations</h1>
      <Suspense fallback={<p className={styles.mapStatus}>Loading map…</p>}>
        <CampusMap
          suppliers={mapped}
          selectedId={selected?.id ?? null}
          onSelect={setSelectedId}
        />
      </Suspense>
      <aside className={styles.panel}>
        <label className={styles.search}>
          <span className={styles.srOnly}>Search locations</span>
          <input
            type="search"
            placeholder="Enter search"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
          />
        </label>
        <div className={styles.tools}>
          <label>
            Sort
            <select
              aria-label="Sort"
              value={sort}
              onChange={(event) => setSort(event.target.value as SortMode)}
            >
              <option value="name">Name</option>
              <option value="open">Open now</option>
            </select>
          </label>
          <label>
            Filter
            <select
              aria-label="Filter"
              value={filter}
              onChange={(event) => setFilter(event.target.value as FilterMode)}
            >
              <option value="all">All locations</option>
              <option value="open">Open now</option>
              {SUPPLIER_CATEGORIES.map((category) => (
                <option key={category} value={category}>
                  {categoryLabels[category]}
                </option>
              ))}
            </select>
          </label>
        </div>
        {suppliers.isPending ? (
          <p className={styles.feedback} role="status">
            Loading locations…
          </p>
        ) : null}
        {suppliers.isError ? (
          <p className={styles.feedback} role="alert">
            Unable to load locations. Please try again.
          </p>
        ) : null}
        {suppliers.isSuccess && visible.length === 0 ? (
          <p className={styles.feedback}>No locations match your search.</p>
        ) : null}
        {suppliers.isSuccess && visible.length > 0 ? (
          <ul className={styles.list} aria-label="Available locations">
            {visible.map((supplier) => {
              const open = describeOpenStatus(supplier.openingHours, now)
              return (
                <li key={supplier.id}>
                  <button
                    type="button"
                    className={
                      selected?.id === supplier.id ? styles.selected : styles.card
                    }
                    aria-pressed={selected?.id === supplier.id}
                    onClick={() => setSelectedId(supplier.id)}
                  >
                    <span>
                      <strong>{supplier.name}</strong>
                      <span className={styles.category}>
                        {categoryLabels[supplier.category]}
                      </span>
                      <span
                        className={
                          open === 'Closed' ? styles.closed : styles.open
                        }
                      >
                        {open}
                      </span>
                      {!hasCoordinates(supplier) && (
                        <span className={styles.unmapped}>Not on the map</span>
                      )}
                    </span>
                    <ChevronRight size={18} aria-hidden="true" />
                  </button>
                </li>
              )
            })}
          </ul>
        ) : null}
      </aside>
      <SupplierSlideOver
        supplier={selected}
        modal={false}
        from="map"
        onClose={() => setSelectedId(null)}
      />
    </section>
  )
}
