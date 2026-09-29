import { useMemo } from 'react'
import type { ReactNode } from 'react'
import { Link } from '@tanstack/react-router'
import DataTable from 'react-data-table-component'
import type { TableColumn } from 'react-data-table-component'
import type { User, UserListParams } from '../../../../types/user.types'
import type { UserListSearch } from '../../utils/userListSearch'
import styles from './UserTable.module.scss'

const EMPTY_USERS: User[] = []
const PAGINATION_OPTIONS = { noRowsPerPage: true }

type UserTableProps = {
  users?: User[]
  total: number
  page: number
  sort: UserListParams['sort']
  listSearch: UserListSearch
  isLoading: boolean
  isUpdating: boolean
  isRefreshing: boolean
  emptyContent: ReactNode
  onPageChange: (page: number) => void
  onSortChange: (sort: UserListParams['sort']) => void
}

export const UserTable = ({
  users,
  total,
  page,
  sort,
  listSearch,
  isLoading,
  isUpdating,
  isRefreshing,
  emptyContent,
  onPageChange,
  onSortChange,
}: UserTableProps) => {
  const { q, role, sort: listSort, page: listPage } = listSearch
  const columns = useMemo<TableColumn<User>[]>(
    () => [
      {
        id: 'name',
        name: 'Name',
        selector: (user) => user.name,
        sortable: true,
      },
      {
        id: 'email',
        name: 'Email',
        selector: (user) => user.email,
        sortable: true,
      },
      {
        id: 'roles',
        name: 'Roles',
        selector: (user) => user.roles.join(', '),
      },
      {
        id: 'actions',
        name: 'Actions',
        cell: (user) => (
          <Link
            to="/admin/users/$userId/edit"
            params={{ userId: user.id }}
            search={{ q, role, sort: listSort, page: listPage }}
            className={styles.actionLink}
          >
            Edit
          </Link>
        ),
      },
    ],
    [q, role, listSort, listPage],
  )

  return (
    <div className={styles.tableShell}>
      {isUpdating && (
        <div
          className={styles.updatingOverlay}
          role="status"
          aria-label="Updating users"
        >
          <span className={styles.spinner} aria-hidden="true" />
        </div>
      )}
      {isRefreshing && (
        <span
          className={`${styles.spinner} ${styles.refreshIndicator}`}
          role="status"
          aria-label="Updating users"
        />
      )}
      <DataTable
        key={sort}
        ariaLabel="Users"
        columns={columns}
        data={users ?? EMPTY_USERS}
        keyField="id"
        defaultSortFieldId={sort.split(',')[0]}
        defaultSortAsc={sort.endsWith('asc')}
        sortServer
        onSort={(column, direction, _rows, sortColumns) => {
          const nextSort: UserListParams['sort'] = sortColumns.length
            ? `${column.id === 'email' ? 'email' : 'name'},${direction}`
            : 'name,asc'
          if (nextSort !== sort) onSortChange(nextSort)
        }}
        pagination
        paginationServer
        paginationPage={page}
        paginationPerPage={20}
        paginationTotalRows={total}
        paginationComponentOptions={PAGINATION_OPTIONS}
        onChangePage={onPageChange}
        progressPending={isLoading}
        persistTableHead
        noDataComponent={emptyContent}
        disabled={isUpdating}
        responsive
      />
    </div>
  )
}
