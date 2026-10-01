import {
  createRootRouteWithContext,
  createRoute,
  redirect,
  Outlet,
  Link,
} from '@tanstack/react-router'

import { AuthLayout } from '#/features/auth/layouts/AuthLayout/AuthLayout'
import { LoginPage } from '#/features/auth/pages/LoginPage/LoginPage'
import { RegisterPage } from '#/features/auth/pages/RegisterPage/RegisterPage'
import { AdminLayout } from '#/layouts/AdminLayout/AdminLayout'
import { AdminOverviewPage } from '#/pages/AdminOverviewPage/AdminOverviewPage'
import { SupplierListPage } from '#/features/suppliers/pages/SupplierListPage/SupplierListPage'
import { SupplierCreatePage } from '#/features/suppliers/pages/SupplierCreatePage/SupplierCreatePage'
import { SupplierEditPage } from '#/features/suppliers/pages/SupplierEditPage/SupplierEditPage'
import { parseSupplierListSearch } from '#/features/suppliers/utils/supplierFilters'
import { UserLayoutPreview } from '#/features/auth/pages/UserLayoutPreview/UserLayoutPreview'
import { AppHomePage } from '#/pages/AppHomePage/AppHomePage'
import { UserLayout } from '#/layouts/UserLayout'
import { CourierHomePage } from '#/features/orders/pages/CourierHomePage/CourierHomePage'
import type { AuthOperations } from '#/features/auth/providers/AuthProvider'
import {
  SessionRecoveryPending,
  SessionRecoveryError,
} from '#/features/auth/components/SessionRecoveryFeedback/SessionRecoveryFeedback'

const rootRoute = createRootRouteWithContext<{ auth: AuthOperations }>()()

const indexRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/',
  beforeLoad: () => {
    throw redirect({ to: '/app', replace: true })
  },
})

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
  pendingComponent: SessionRecoveryPending,
  errorComponent: SessionRecoveryError,
})

const appRoute = createRoute({
  getParentRoute: () => protectedRoute,
  path: '/app',
  component: AppHomePage,
})

// Below are admin routes
// Admin pages require a signed-in session.
const adminLayoutRoute = createRoute({
  getParentRoute: () => protectedRoute,
  path: '/admin',
  component: AdminLayout,
})

const adminIndexRoute = createRoute({
  getParentRoute: () => adminLayoutRoute,
  path: '/',
  component: AdminOverviewPage,
})

const supplierListRoute = createRoute({
  getParentRoute: () => adminLayoutRoute,
  path: '/suppliers',
  component: SupplierListPage,
  validateSearch: parseSupplierListSearch,
})

const supplierCreateRoute = createRoute({
  getParentRoute: () => adminLayoutRoute,
  path: '/suppliers/new',
  component: SupplierCreatePage,
})

const supplierEditRoute = createRoute({
  getParentRoute: () => adminLayoutRoute,
  path: '/suppliers/$supplierId/edit',
  component: SupplierEditPage,
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
  protectedRoute.addChildren([
    appRoute,
    adminLayoutRoute.addChildren([
      adminIndexRoute,
      supplierListRoute,
      supplierCreateRoute,
      supplierEditRoute,
    ]),
  ]),
  authLayoutRoute.addChildren([loginRoute, registerRoute]),
])
