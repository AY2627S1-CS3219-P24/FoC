import {
  createRootRouteWithContext,
  createRoute,
  Outlet,
  redirect,
} from '@tanstack/react-router'

import { LoginPage } from '#/features/auth/pages/LoginPage/LoginPage'
import { RegisterPage } from '#/features/auth/pages/RegisterPage/RegisterPage'
import { UserLayoutPreview } from '#/features/auth/pages/UserLayoutPreview/UserLayoutPreview'
import { AppHomePage } from '#/pages/AppHomePage/AppHomePage'
import { ErrorPage } from '#/pages/ErrorPage'
import { LoadingPage } from '#/pages/LoadingPage'
import { AdminLayout } from '#/layouts/AdminLayout/AdminLayout'
import { UserListPage } from '#/features/users/pages/UserListPage/UserListPage'
import { UserEditPage } from '#/features/users/pages/UserEditPage/UserEditPage'
import { validateUserListSearch } from '#/features/users/pages/UserListPage/utils/userListSearch'
import { AuthLayout } from '#/features/auth/layouts/AuthLayout/AuthLayout'
import type { AuthOperations } from '#/features/auth/providers/AuthProvider'
import { hasAdminRole } from '#/features/auth/utils/hasAdminRole'

const rootRoute = createRootRouteWithContext<{ auth: AuthOperations }>()()

const indexRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/',
  beforeLoad: () => {
    throw redirect({ to: '/app', replace: true })
  },
})

// Share the auth layout without adding a URL prefix.
const authLayoutRoute = createRoute({
  getParentRoute: () => rootRoute,
  id: 'auth',
  component: AuthLayout,
})

const loginRoute = createRoute({
  getParentRoute: () => authLayoutRoute,
  component: LoginPage,
  path: '/login',
  validateSearch: (
    search: Record<string, unknown>,
  ): { registered?: boolean } => ({
    registered: search.registered === true ? true : undefined,
  }),
})

const registerRoute = createRoute({
  getParentRoute: () => authLayoutRoute,
  component: RegisterPage,
  path: '/register',
})

const protectedRoute = createRoute({
  getParentRoute: () => rootRoute,
  id: 'protected',
  component: Outlet,
  beforeLoad: async ({ context }) => {
    if (!(await context.auth.ensureAuthenticated())) {
      throw redirect({ to: '/login', replace: true })
    }
  },
  pendingMs: 500,
  pendingMinMs: 0,
  pendingComponent: LoadingPage,
  errorComponent: ErrorPage,
})

const appRoute = createRoute({
  getParentRoute: () => protectedRoute,
  path: '/app',
  component: AppHomePage,
})

// Below are admin routes
const adminLayoutRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/admin',
  component: AdminLayout,
  beforeLoad: async ({ context }) => {
    if (!(await context.auth.ensureAuthenticated())) {
      throw redirect({ to: '/login', replace: true })
    }
    if (!hasAdminRole()) {
      throw redirect({ to: '/app', replace: true })
    }
  },
  pendingMs: 500,
  pendingMinMs: 0,
  pendingComponent: LoadingPage,
  errorComponent: ErrorPage,
})

const adminIndexRoute = createRoute({
  getParentRoute: () => adminLayoutRoute,
  path: '/',
  beforeLoad: () => {
    throw redirect({ to: '/admin/users', replace: true })
  },
})

const userListRoute = createRoute({
  getParentRoute: () => adminLayoutRoute,
  path: '/users',
  component: UserListPage,
  validateSearch: validateUserListSearch,
})

const userEditRoute = createRoute({
  getParentRoute: () => adminLayoutRoute,
  path: '/users/$userId/edit',
  component: UserEditPage,
  validateSearch: validateUserListSearch,
})

export const routeTree = rootRoute.addChildren([
  ...(import.meta.env.DEV
    ? [
        createRoute({
          getParentRoute: () => rootRoute,
          path: '/preview/user-layout',
          component: UserLayoutPreview,
        }),
      ]
    : []),
  indexRoute,
  protectedRoute.addChildren([appRoute]),
  authLayoutRoute.addChildren([loginRoute, registerRoute]),
  adminLayoutRoute.addChildren([adminIndexRoute, userListRoute, userEditRoute]),
])
