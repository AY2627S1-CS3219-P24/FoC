import { Toast } from '@base-ui/react/toast'
import { Link, useNavigate, useParams, useSearch } from '@tanstack/react-router'
import { useUpdateUserMutation } from '../../hooks/useUpdateUserMutation'
import { useUserQuery } from '../../hooks/useUserQuery'
import type { UserFormValues } from '../../schemas/user.schema'
import type { User } from '../../types/user.types'
import { getUserErrorMessage } from '../../utils/userErrors'
import { UserEditForm } from './components/UserEditForm/UserEditForm'
import { UserEditSkeleton } from './components/UserEditSkeleton/UserEditSkeleton'
import styles from './UserEditPage.module.scss'

export const UserEditPage = () => {
  const { userId } = useParams({ from: '/admin/users/$userId/edit' })
  const listSearch = useSearch({ from: '/admin/users/$userId/edit' })
  const navigate = useNavigate()
  const toast = Toast.useToastManager()
  const { data: user, error: userError } = useUserQuery(userId)
  const {
    mutateAsync: updateUserAsync,
    isPending: isUpdatingUser,
    error: updateError,
  } = useUpdateUserMutation()

  const userErrorMessage = userError
    ? getUserErrorMessage(userError)
    : undefined
  const updateErrorMessage = updateError
    ? getUserErrorMessage(updateError)
    : undefined
  const isInitialLoading = !user && !userError
  const handleBackToList = () => {
    void navigate({ to: '/admin/users', search: listSearch })
  }
  const handleSubmitUser = async (values: UserFormValues) => {
    let updated: User
    try {
      updated = await updateUserAsync({ id: userId, request: values })
    } catch {
      return
    }
    toast.add({ type: 'success', title: `${updated.name} was updated.` })
    await navigate({ to: '/admin/users', search: listSearch, replace: true })
  }

  return (
    <section className={styles.page}>
      <header className={styles.header}>
        <Link to="/admin/users" search={listSearch} className={styles.backLink}>
          ← Users
        </Link>
        <h1>Edit user</h1>
      </header>
      {isInitialLoading && <UserEditSkeleton />}
      {userErrorMessage && (
        <p className={styles.error} role="alert">
          {userErrorMessage}
        </p>
      )}
      {user && (
        <UserEditForm
          key={user.id}
          user={user}
          isSaving={isUpdatingUser}
          submitError={updateErrorMessage}
          onSubmit={handleSubmitUser}
          onCancel={handleBackToList}
        />
      )}
    </section>
  )
}
