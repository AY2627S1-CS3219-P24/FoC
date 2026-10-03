import {
  createRootRouteWithContext,
  createRoute,
  redirect,
  Outlet,
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
import { AccountLayout } from '#/features/auth/layouts/AccountLayout'
import { EditProfilePage } from '#/features/auth/pages/EditProfilePage/EditProfilePage'
import { ProfilePage } from '#/features/auth/pages/ProfilePage/ProfilePage'
import { HomePage } from '#/features/orders/pages/HomePage/HomePage'
import { getHomeMode } from '#/features/orders/utils/homeMode'
import type { HomeMode } from '#/features/orders/utils/homeMode'
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
    throw redirect({ to: '/home', replace: true })
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

const authenticatedRoute = createRoute({
  getParentRoute: () => rootRoute,
  id: 'authenticated',
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

const userRoute = createRoute({
  getParentRoute: () => authenticatedRoute,
  id: 'user',
  component: AccountLayout,
})

const profileRoute = createRoute({
  getParentRoute: () => userRoute,
  path: '/profile',
  component: ProfilePage,
})

const editProfileRoute = createRoute({
  getParentRoute: () => userRoute,
  path: '/profile/edit',
  component: EditProfilePage,
})

const homeRoute = createRoute({
  getParentRoute: () => userRoute,
  path: '/home',
  validateSearch: (search: Record<string, unknown>): { mode?: HomeMode } => ({
    mode:
      search.mode === 'requestor' || search.mode === 'courier'
        ? search.mode
        : undefined,
  }),
  beforeLoad: ({ search }) => {
    if (!search.mode) {
      throw redirect({
        to: '/home',
        search: { mode: getHomeMode() },
        replace: true,
      })
    }
  },
  component: HomePage,
})

// Below are admin routes
// Admin pages require a signed-in session.
const adminLayoutRoute = createRoute({
  getParentRoute: () => authenticatedRoute,
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
  authenticatedRoute.addChildren([
    // Student pages share the top-header account layout.
    userRoute.addChildren([homeRoute, profileRoute, editProfileRoute]),
    // Admin pages have their own sidebar layout.
    adminLayoutRoute.addChildren([
      adminIndexRoute,
      supplierListRoute,
      supplierCreateRoute,
      supplierEditRoute,
    ]),
  ]),
  authLayoutRoute.addChildren([loginRoute, registerRoute]),
])
