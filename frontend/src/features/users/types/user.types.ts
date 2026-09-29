import type { Page } from '#/types/page.types'

export type UserRole = 'USER' | 'ADMIN'

export type User = {
  id: string
  email: string
  name: string
  roles: UserRole[]
}

export type UserPage = Page<User>

export type UserListParams = {
  page: number
  size: number
  search: string
  role?: UserRole
  sort: 'name,asc' | 'name,desc' | 'email,asc' | 'email,desc'
}

export type UpdateUserRequest = Pick<User, 'name' | 'email' | 'roles'>
