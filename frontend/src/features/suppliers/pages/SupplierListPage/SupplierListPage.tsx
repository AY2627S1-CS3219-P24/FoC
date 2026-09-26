import { useMemo, useState } from 'react'
import { Link } from '@tanstack/react-router'
import { AlertDialog } from '@base-ui/react/alert-dialog'
import { Button } from '@base-ui/react/button'
import { Input } from '@base-ui/react/input'
import { Switch } from '@base-ui/react/switch'
import { Toast } from '@base-ui/react/toast'
import { useSetSupplierActive, useSuppliers } from '../../hooks/useSuppliers'
import {
  categoryLabels,
  formatLocation,
  formatOpeningHours,
  getErrorMessage,
} from '../../utils/supplierFormat'
import type { Supplier } from '../../types/supplier.types'
import ui from '../../styles/supplier.module.scss'
import styles from './SupplierListPage.module.scss'

export const SupplierListPage = () => {
  const [showInactive, setShowInactive] = useState(false)
  const [search, setSearch] = useState('')
  const [pendingDeactivation, setPendingDeactivation] =
    useState<Supplier | null>(null)
  const suppliers = useSuppliers(showInactive)
  const setActive = useSetSupplierActive()
  const toast = Toast.useToastManager()

  const visibleSuppliers = useMemo(() => {
    const query = search.trim().toLowerCase()
    if (!query) return suppliers.data ?? []
    return (suppliers.data ?? []).filter((supplier) =>
      [supplier.name, supplier.building, categoryLabels[supplier.category]]
        .join(' ')
        .toLowerCase()
        .includes(query),
    )
  }, [suppliers.data, search])

  const changeActive = (supplier: Supplier, active: boolean) =>
    setActive.mutate(
      { id: supplier.id, active },
      {
        onSuccess: () => {
          setPendingDeactivation(null)
          toast.add({
            type: 'success',
            title: `${supplier.name} was ${active ? 'reactivated' : 'deactivated'}.`,
          })
        },
        onError: (error) =>
          toast.add({ type: 'error', title: getErrorMessage(error) }),
      },
    )

  return (
    <section className={ui.page}>
      <header className={ui.header}>
        <h1 className={ui.title}>Supplier Management</h1>
        <Link
          to="/admin/suppliers/new"
          className={`${ui.button} ${ui.primary}`}
        >
          + Add Supplier
        </Link>
      </header>

      <div className={styles.toolbar}>
        <Input
          type="search"
          placeholder="Search by name, building or category"
          aria-label="Search suppliers"
          className={`${ui.input} ${styles.search}`}
          value={search}
          onValueChange={setSearch}
        />
        <label className={styles.switchLabel}>
          <Switch.Root
            className={styles.switch}
            checked={showInactive}
            onCheckedChange={setShowInactive}
          >
            <Switch.Thumb className={styles.thumb} />
          </Switch.Root>
          Show inactive suppliers
        </label>
      </div>

      {suppliers.isPending && <p className={ui.muted}>Loading suppliers…</p>}
      {suppliers.isError && (
        <p className={ui.alert} role="alert">
          {getErrorMessage(suppliers.error)}
        </p>
      )}
      {suppliers.isSuccess && (
        <div className={`${ui.card} ${styles.tableWrapper}`}>
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
              {visibleSuppliers.map((supplier) => (
                <tr key={supplier.id}>
                  <td>
                    <span className={styles.name}>{supplier.name}</span>
                    {!supplier.active && (
                      <span className={styles.badge}>Deactivated</span>
                    )}
                  </td>
                  <td>{categoryLabels[supplier.category]}</td>
                  <td>{formatLocation(supplier)}</td>
                  <td className={styles.nowrap}>
                    {formatOpeningHours(supplier)}
                  </td>
                  <td>
                    <div className={styles.actions}>
                      <Link
                        to="/admin/suppliers/$supplierId/edit"
                        params={{ supplierId: supplier.id }}
                        className={`${ui.button} ${ui.small}`}
                      >
                        Edit
                      </Link>
                      {supplier.active ? (
                        <Button
                          className={`${ui.button} ${ui.small} ${ui.danger}`}
                          onClick={() => setPendingDeactivation(supplier)}
                        >
                          Deactivate
                        </Button>
                      ) : (
                        <Button
                          className={`${ui.button} ${ui.small}`}
                          disabled={setActive.isPending}
                          onClick={() => changeActive(supplier, true)}
                        >
                          Reactivate
                        </Button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
              {visibleSuppliers.length === 0 && (
                <tr>
                  <td colSpan={5} className={styles.empty}>
                    No suppliers found.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}

      <AlertDialog.Root
        open={pendingDeactivation !== null}
        onOpenChange={(open) => !open && setPendingDeactivation(null)}
      >
        <AlertDialog.Portal>
          <AlertDialog.Backdrop className={styles.backdrop} />
          <AlertDialog.Popup className={styles.dialog}>
            <AlertDialog.Title className={styles.dialogTitle}>
              Deactivate this supplier?
            </AlertDialog.Title>
            <AlertDialog.Description className={ui.muted}>
              {pendingDeactivation?.name} will be hidden from users and can no
              longer get new errands. You can reactivate it later.
            </AlertDialog.Description>
            <div className={styles.dialogActions}>
              <AlertDialog.Close className={ui.button}>
                Cancel
              </AlertDialog.Close>
              <Button
                className={`${ui.button} ${ui.danger}`}
                disabled={setActive.isPending}
                onClick={() =>
                  pendingDeactivation &&
                  changeActive(pendingDeactivation, false)
                }
              >
                Deactivate
              </Button>
            </div>
          </AlertDialog.Popup>
        </AlertDialog.Portal>
      </AlertDialog.Root>
    </section>
  )
}
