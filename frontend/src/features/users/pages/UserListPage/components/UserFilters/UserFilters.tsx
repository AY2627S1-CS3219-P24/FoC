import { Input } from '@base-ui/react/input'
import { Button } from '@base-ui/react/button'
import { UNASSIGNED_FACULTY } from '#/constants/faculty'
import type { UserRole } from '../../../../types/user.types'
import styles from './UserFilters.module.scss'

type UserFiltersProps = {
  searchInput: string
  role: UserRole | ''
  faculty: string
  faculties: string[]
  facultyError: boolean
  onSearchChange: (value: string) => void
  onRoleChange: (role: UserRole | '') => void
  onFacultyChange: (faculty: string) => void
  onRetryFaculties: () => void
}

export const UserFilters = ({
  searchInput,
  role,
  faculty,
  faculties,
  facultyError,
  onSearchChange,
  onRoleChange,
  onFacultyChange,
  onRetryFaculties,
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
      <label className={styles.filterLabel}>
        Faculty
        <select
          className={styles.roleFilter}
          value={faculty}
          onChange={(event) => onFacultyChange(event.target.value)}
          disabled={facultyError || faculties.length === 0}
        >
          <option value="">All faculties</option>
          <option value={UNASSIGNED_FACULTY}>No faculty</option>
          {faculties.map((name) => (
            <option key={name} value={name}>
              {name}
            </option>
          ))}
        </select>
      </label>
      {facultyError && (
        <Button type="button" onClick={onRetryFaculties}>
          Retry faculty choices
        </Button>
      )}
    </div>
  )
}
