import { useEffect, useState } from 'react'
import { Button } from '@base-ui/react/button'
import { useNavigate, useSearch } from '@tanstack/react-router'
import { useUsersQuery } from '../../hooks/useUsersQuery'
import type { UserListParams, UserRole } from '../../types/user.types'
import { getUserErrorMessage } from '../../utils/userErrors'
import { UserFilters } from './components/UserFilters/UserFilters'
import { UserListNoData } from './components/UserListNoData/UserListNoData'
import { UserTable } from './components/UserTable/UserTable'
import { getUserListParams } from './utils/userListSearch'
import styles from './UserListPage.module.scss'

export const UserListPage = () => {
  const search = useSearch({ from: '/admin/users' })
  const navigate = useNavigate({ from: '/admin/users' })
  const { q, role, sort, page } = search
  const [searchInput, setSearchInput] = useState(q ?? '')
  const params = getUserListParams(search)
  const {
    data: usersPage,
    error: usersError,
    isFetching: isUsersFetching,
    isPlaceholderData: isShowingPreviousResults,
    refetch: retryUsers,
  } = useUsersQuery(params)

  useEffect(() => setSearchInput(q ?? ''), [q])

  useEffect(() => {
    const nextSearch = searchInput.trim()
    if (nextSearch === (q ?? '')) return
    const timer = window.setTimeout(() => {
      void navigate({
        search: (previous) => ({
          ...previous,
          q: nextSearch || undefined,
          page: undefined,
        }),
        replace: true,
      })
    }, 300)
    return () => window.clearTimeout(timer)
  }, [searchInput, q, navigate])

  useEffect(() => {
    if (!usersPage || isShowingPreviousResults) return
    const lastPage = Math.max(1, Math.ceil(usersPage.total / params.size))
    if ((page ?? 1) > lastPage) {
      void navigate({
        search: (previous) => ({
          ...previous,
          page: lastPage === 1 ? undefined : lastPage,
        }),
        replace: true,
      })
    }
  }, [usersPage, isShowingPreviousResults, page, params.size, navigate])

  const usersErrorMessage = usersError
    ? getUserErrorMessage(usersError)
    : undefined
  const isInitialLoading = !usersPage && !usersError
  const isUpdating = isShowingPreviousResults && isUsersFetching
  const isRefreshing = isUsersFetching && !isInitialLoading && !isUpdating
  const hasFilters = Boolean(q || role)
  const handleRoleChange = (nextRole: UserRole | '') => {
    void navigate({
      search: (previous) => ({
        ...previous,
        role: nextRole || undefined,
        page: undefined,
      }),
    })
  }
  const handleSortChange = (nextSort: UserListParams['sort']) => {
    void navigate({
      search: (previous) => ({
        ...previous,
        sort: nextSort === 'name,asc' ? undefined : nextSort,
        page: undefined,
      }),
    })
  }
  const handlePageChange = (nextPage: number) => {
    if (nextPage === (page ?? 1)) return
    void navigate({
      search: (previous) => ({
        ...previous,
        page: nextPage === 1 ? undefined : nextPage,
      }),
    })
  }
  const handleClearFilters = () => {
    setSearchInput('')
    void navigate({
      search: (previous) => ({
        ...previous,
        q: undefined,
        role: undefined,
        page: undefined,
      }),
    })
  }
  const handleRetry = () => void retryUsers()

  return (
    <section className={styles.page}>
      <header className={styles.header}>
        <h1>Users</h1>
      </header>

      <UserFilters
        searchInput={searchInput}
        role={role ?? ''}
        onSearchChange={setSearchInput}
        onRoleChange={handleRoleChange}
      />

      {usersErrorMessage && usersPage && (
        <div className={styles.error} role="alert">
          <span>{usersErrorMessage}</span>
          <Button className={styles.retryButton} onClick={handleRetry}>
            Retry
          </Button>
        </div>
      )}

      <UserTable
        users={usersPage?.items}
        total={usersPage?.total ?? 0}
        page={page ?? 1}
        sort={sort ?? 'name,asc'}
        listSearch={search}
        isLoading={isInitialLoading}
        isUpdating={isUpdating}
        isRefreshing={isRefreshing}
        emptyContent={
          <UserListNoData
            errorMessage={usersErrorMessage}
            hasFilters={hasFilters}
            onClearFilters={handleClearFilters}
            onRetry={handleRetry}
          />
        }
        onPageChange={handlePageChange}
        onSortChange={handleSortChange}
      />
    </section>
  )
}
