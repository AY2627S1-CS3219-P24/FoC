import { Link, useParams } from '@tanstack/react-router'
import { Toast } from '@base-ui/react/toast'
import { SupplierForm } from '../../components/SupplierForm/SupplierForm'
import { useSupplier, useUpdateSupplier } from '../../hooks/useSuppliers'
import {
  toSupplierFormValues,
  toSupplierRequest,
} from '../../schemas/supplier.schema'
import { getErrorMessage } from '../../utils/supplierFormat'
import { useBackToSupplierList } from '../../hooks/useBackToSupplierList'
import ui from '../../styles/supplier.module.scss'

export const SupplierEditPage = () => {
  const { supplierId } = useParams({
    from: '/protected/admin/suppliers/$supplierId/edit',
  })
  const toast = Toast.useToastManager()
  const supplier = useSupplier(supplierId)
  const updateSupplier = useUpdateSupplier(supplierId)
  const backToList = useBackToSupplierList()

  return (
    <section className={ui.page}>
      <div>
        <Link to="/admin/suppliers" className={ui.backLink}>
          ← Suppliers
        </Link>
        <h1 className={ui.title}>Edit Supplier</h1>
      </div>
      {supplier.isPending && <p className={ui.muted}>Loading…</p>}
      {supplier.isError && (
        <p className={ui.alert} role="alert">
          {getErrorMessage(supplier.error)}
        </p>
      )}
      {supplier.isSuccess && (
        <SupplierForm
          defaultValues={toSupplierFormValues(supplier.data)}
          submitLabel="Save Changes"
          submitting={updateSupplier.isPending}
          onCancel={backToList}
          onSubmit={(values) =>
            updateSupplier.mutate(toSupplierRequest(values), {
              onSuccess: (updated) => {
                toast.add({
                  type: 'success',
                  title: `${updated.name} was updated.`,
                })
                backToList()
              },
              onError: (error) =>
                toast.add({ type: 'error', title: getErrorMessage(error) }),
            })
          }
        />
      )}
    </section>
  )
}
