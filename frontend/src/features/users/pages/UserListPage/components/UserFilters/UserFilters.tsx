import { Input } from '@base-ui/react/input'
import type { UserRole } from '../../../../types/user.types'
import styles from './UserFilters.module.scss'

type UserFiltersProps = {
  searchInput: string
  role: UserRole | ''
  onSearchChange: (value: string) => void
  onRoleChange: (role: UserRole | '') => void
}

export const UserFilters = ({
  searchInput,
  role,
  onSearchChange,
  onRoleChange,
}: UserFiltersProps) => {
  const handleRoleChange = (event: React.ChangeEvent<HTMLSelectElement>) =>
    onRoleChange(event.target.value as UserRole | '')

  return (
    <div className={styles.toolbar}>
      <Input
        type="search"
        aria-label="Search users by name or email"
        placeholder="Search name or email"
        className={styles.search}
        value={searchInput}
        onValueChange={onSearchChange}
      />
      <label className={styles.filterLabel}>
        Role
        <select
          className={styles.roleFilter}
          value={role}
          onChange={handleRoleChange}
        >
          <option value="">All roles</option>
          <option value="USER">User</option>
          <option value="ADMIN">Admin</option>
        </select>
      </label>
    </div>
  )
}
