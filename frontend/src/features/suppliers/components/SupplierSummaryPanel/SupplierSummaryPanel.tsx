import { Link } from '@tanstack/react-router'
import { Panel } from '#/components/Panel/Panel'
import {
  categoryLabels,
  formatLocation,
  getErrorMessage,
} from '../../utils/supplierFormat'
import { formatWeeklyHours } from '../../utils/openingHours'
import type { Supplier } from '../../types/supplier.types'
import ui from '../../styles/supplier.module.scss'
import styles from './SupplierSummaryPanel.module.scss'

type SupplierSummaryPanelProps = {
  suppliers: Array<Supplier> | undefined
  isPending: boolean
  error: unknown
  limit?: number
}

// only show 5 suppliers on dashboard page
export const SupplierSummaryPanel = ({
  suppliers,
  isPending,
  error,
  limit = 5,
}: SupplierSummaryPanelProps) => {
  const visible = (suppliers ?? [])
    .filter((supplier) => supplier.active)
    .slice(0, limit)

  return (
    <Panel
      title="Suppliers"
      action={
        <Link to="/admin/suppliers" className={`${ui.button} ${ui.small}`}>
          View all
        </Link>
      }
    >
      {isPending && <p className={ui.muted}>Loading suppliers…</p>}
      {Boolean(error) && (
        <p className={ui.alert} role="alert">
          {getErrorMessage(error)}
        </p>
      )}
      {!isPending && !error && (
        <div className={styles.tableWrapper}>
          <table className={styles.table}>
            <thead>
              <tr>
                <th>Name</th>
                <th>Category</th>
                <th>Location</th>
                <th>Hours</th>
                <th>
                  <span className={styles.visuallyHidden}>Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {visible.map((supplier) => (
                <tr key={supplier.id}>
                  <td className={styles.name}>{supplier.name}</td>
                  <td>{categoryLabels[supplier.category]}</td>
                  <td>{formatLocation(supplier)}</td>
                  <td className={styles.nowrap}>
                    {formatWeeklyHours(supplier.openingHours).map((line) => (
                      <span key={line} className={styles.hoursLine}>
                        {line}
                      </span>
                    ))}
                  </td>
                  <td className={styles.actions}>
                    <Link
                      to="/admin/suppliers/$supplierId/edit"
                      params={{ supplierId: supplier.id }}
                      className={`${ui.button} ${ui.small}`}
                    >
                      Edit
                    </Link>
                  </td>
                </tr>
              ))}
              {visible.length === 0 && (
                <tr>
                  <td colSpan={5} className={styles.empty}>
                    No active suppliers yet.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}
    </Panel>
  )
}
