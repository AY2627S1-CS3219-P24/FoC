import { useCanGoBack, useNavigate, useRouter } from '@tanstack/react-router'

/**
 * Returns to wherever the admin came from (the filtered list or the overview),
 * or to the supplier list when the page was opened directly.
 */
export const useBackToSupplierList = () => {
  const router = useRouter()
  const canGoBack = useCanGoBack()
  const navigate = useNavigate()

  return () => {
    if (canGoBack) router.history.back()
    else void navigate({ to: '/admin/suppliers' })
  }
}
