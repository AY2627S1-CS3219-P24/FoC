import { useNavigate } from '@tanstack/react-router'
import { Toast } from '@base-ui/react/toast'
import { SupplierForm } from '../../components/SupplierForm/SupplierForm'
import { useCreateSupplier } from '../../hooks/useSuppliers'
import {
  emptySupplierForm,
  toSupplierRequest,
} from '../../schemas/supplier.schema'
import { getErrorMessage } from '../../utils/supplierFormat'
import ui from '../../styles/supplier.module.scss'

export const SupplierCreatePage = () => {
  const navigate = useNavigate()
  const toast = Toast.useToastManager()
  const createSupplier = useCreateSupplier()
  const backToList = () => navigate({ to: '/admin/suppliers' })

  return (
    <section className={ui.page}>
      <h1 className={ui.title}>Create Supplier</h1>
      <SupplierForm
        defaultValues={emptySupplierForm}
        submitLabel="Add Supplier"
        submitting={createSupplier.isPending}
        onCancel={backToList}
        onSubmit={(values) =>
          createSupplier.mutate(toSupplierRequest(values), {
            onSuccess: (supplier) => {
              toast.add({
                type: 'success',
                title: `${supplier.name} was added.`,
              })
              void backToList()
            },
            onError: (error) =>
              toast.add({ type: 'error', title: getErrorMessage(error) }),
          })
        }
      />
    </section>
  )
}
