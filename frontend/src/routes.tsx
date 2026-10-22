import {
  createRootRouteWithContext,
  createRoute,
  Outlet,
  redirect,
} from '@tanstack/react-router'

import { LoginPage } from '#/features/auth/pages/LoginPage/LoginPage'
import { RegisterPage } from '#/features/auth/pages/RegisterPage/RegisterPage'
import { ForgotPasswordPage } from '#/features/auth/pages/ForgotPasswordPage/ForgotPasswordPage'
import { ResetPasswordPage } from '#/features/auth/pages/ResetPasswordPage/ResetPasswordPage'
import { VerifyEmailPage } from '#/features/auth/pages/VerifyEmailPage/VerifyEmailPage'
import { AdminOverviewPage } from '#/pages/AdminOverviewPage/AdminOverviewPage'
import { SupplierListPage } from '#/features/suppliers/pages/SupplierListPage/SupplierListPage'
import { SupplierCreatePage } from '#/features/suppliers/pages/SupplierCreatePage/SupplierCreatePage'
import { SupplierEditPage } from '#/features/suppliers/pages/SupplierEditPage/SupplierEditPage'
import { parseSupplierListSearch } from '#/features/suppliers/utils/supplierFilters'
import { UserLayoutPreview } from '#/features/auth/pages/UserLayoutPreview/UserLayoutPreview'
import { AccountLayout } from '#/features/auth/layouts/AccountLayout'
import { EditProfilePage } from '#/features/auth/pages/EditProfilePage/EditProfilePage'
import { ProfilePage } from '#/features/auth/pages/ProfilePage/ProfilePage'
import { ErrorPage } from '#/pages/ErrorPage'
import { LoadingPage } from '#/pages/LoadingPage'
import { AdminLayout } from '#/layouts/AdminLayout/AdminLayout'
import { UserListPage } from '#/features/users/pages/UserListPage/UserListPage'
import { UserEditPage } from '#/features/users/pages/UserEditPage/UserEditPage'
import { UserViewPage } from '#/features/users/pages/UserViewPage/UserViewPage'
import { validateUserListSearch } from '#/features/users/pages/UserListPage/utils/userListSearch'
import { AuthLayout } from '#/features/auth/layouts/AuthLayout/AuthLayout'
import { HomePage } from '#/features/orders/pages/HomePage/HomePage'
import { MyErrandsPage } from '#/features/orders/pages/MyErrandsPage/MyErrandsPage'
import { getHomeMode } from '#/features/orders/utils/homeMode'
import type { HomeMode } from '#/features/orders/utils/homeMode'
import type { AuthOperations } from '#/features/auth/providers/AuthProvider'
import { hasAdminRole } from '#/features/auth/utils/hasAdminRole'

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
})

const registerRoute = createRoute({
  getParentRoute: () => authLayoutRoute,
  component: RegisterPage,
  path: '/register',
})

const verifyEmailRoute = createRoute({
  getParentRoute: () => authLayoutRoute,
  path: '/verify-email',
  component: VerifyEmailPage,
  validateSearch: (
    search: Record<string, unknown>,
  ): { token?: string; email?: string; purpose?: 'email-change' } => ({
    purpose: search.purpose === 'email-change' ? 'email-change' : undefined,
    token:
      typeof search.token === 'string' && search.token
        ? search.token
        : undefined,
    email:
      typeof search.email === 'string' && search.email
        ? search.email
        : undefined,
  }),
})

const forgotPasswordRoute = createRoute({
  getParentRoute: () => authLayoutRoute,
  path: '/forgot-password',
  component: ForgotPasswordPage,
})

const resetPasswordRoute = createRoute({
  getParentRoute: () => authLayoutRoute,
  path: '/reset-password',
  component: ResetPasswordPage,
  validateSearch: (search: Record<string, unknown>): { token?: string } => ({
    token:
      typeof search.token === 'string' && search.token
        ? search.token
        : undefined,
  }),
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
  pendingComponent: LoadingPage,
  errorComponent: ErrorPage,
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

const myErrandsRoute = createRoute({
  getParentRoute: () => userRoute,
  path: '/my-errands',
  component: MyErrandsPage,
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
      throw redirect({ to: '/home', replace: true })
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

const userViewRoute = createRoute({
  getParentRoute: () => adminLayoutRoute,
  path: '/users/$userId',
  component: UserViewPage,
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
  authenticatedRoute.addChildren([
    // Student pages share the top-header account layout.
    userRoute.addChildren([
      homeRoute,
      myErrandsRoute,
      profileRoute,
      editProfileRoute,
    ]),
    // Admin pages have their own sidebar layout.
    adminLayoutRoute.addChildren([
      adminIndexRoute,
      supplierListRoute,
      supplierCreateRoute,
      supplierEditRoute,
      userListRoute,
      userViewRoute,
      userEditRoute,
    ]),
  ]),
  authLayoutRoute.addChildren([
    loginRoute,
    registerRoute,
    verifyEmailRoute,
    forgotPasswordRoute,
    resetPasswordRoute,
  ]),
])
