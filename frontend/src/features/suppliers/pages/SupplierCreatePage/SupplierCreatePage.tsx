import { Link } from '@tanstack/react-router'
import { Toast } from '@base-ui/react/toast'
import { SupplierForm } from '../../components/SupplierForm/SupplierForm'
import { useCreateSupplier } from '../../hooks/useSuppliers'
import {
  emptySupplierForm,
  toSupplierRequest,
} from '../../schemas/supplier.schema'
import { getErrorMessage } from '../../utils/supplierFormat'
import { useBackToSupplierList } from '../../hooks/useBackToSupplierList'
import ui from '../../styles/supplier.module.scss'

export const SupplierCreatePage = () => {
  const toast = Toast.useToastManager()
  const createSupplier = useCreateSupplier()
  const backToList = useBackToSupplierList()

  return (
    <section className={ui.page}>
      <div>
        <Link to="/admin/suppliers" className={ui.backLink}>
          ← Suppliers
        </Link>
        <h1 className={ui.title}>Create Supplier</h1>
      </div>
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
              backToList()
            },
            onError: (error) =>
              toast.add({ type: 'error', title: getErrorMessage(error) }),
          })
        }
      />
    </section>
  )
}
