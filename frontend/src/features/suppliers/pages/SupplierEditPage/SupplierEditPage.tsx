import { useNavigate, useParams } from '@tanstack/react-router'
import { Toast } from '@base-ui/react/toast'
import { SupplierForm } from '../../components/SupplierForm/SupplierForm'
import { useSupplier, useUpdateSupplier } from '../../hooks/useSuppliers'
import {
  toSupplierFormValues,
  toSupplierRequest,
} from '../../schemas/supplier.schema'
import { getErrorMessage } from '../../utils/supplierFormat'
import ui from '../../styles/supplier.module.scss'

export const SupplierEditPage = () => {
  const { supplierId } = useParams({
    from: '/admin/suppliers/$supplierId/edit',
  })
  const navigate = useNavigate()
  const toast = Toast.useToastManager()
  const supplier = useSupplier(supplierId)
  const updateSupplier = useUpdateSupplier(supplierId)
  const backToList = () => navigate({ to: '/admin/suppliers' })

  return (
    <section className={ui.page}>
      <h1 className={ui.title}>Edit Supplier</h1>
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
                void backToList()
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
