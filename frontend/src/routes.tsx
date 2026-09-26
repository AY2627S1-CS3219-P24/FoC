import { createRootRoute, createRoute, redirect } from '@tanstack/react-router'
import { AuthLayout } from '#/features/auth/layouts/AuthLayout'
import { LoginPage } from '#/features/auth/pages/LoginPage/LoginPage'
import { RegisterPage } from '#/features/auth/pages/RegisterPage/RegisterPage'
import { AdminLayout } from '#/layouts/AdminLayout/AdminLayout'
import { SupplierListPage } from '#/features/suppliers/pages/SupplierListPage/SupplierListPage'
import { SupplierCreatePage } from '#/features/suppliers/pages/SupplierCreatePage/SupplierCreatePage'
import { SupplierEditPage } from '#/features/suppliers/pages/SupplierEditPage/SupplierEditPage'

const rootRoute = createRootRoute()

const indexRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/',
  beforeLoad: () => {
    throw redirect({ to: '/login', replace: true })
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

// Below are admin routes
const adminLayoutRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/admin',
  component: AdminLayout,
})

const adminIndexRoute = createRoute({
  getParentRoute: () => adminLayoutRoute,
  path: '/',
  beforeLoad: () => {
    throw redirect({ to: '/admin/suppliers', replace: true })
  },
})

const supplierListRoute = createRoute({
  getParentRoute: () => adminLayoutRoute,
  path: '/suppliers',
  component: SupplierListPage,
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
  indexRoute,
  authLayoutRoute.addChildren([loginRoute, registerRoute]),
  adminLayoutRoute.addChildren([
    adminIndexRoute,
    supplierListRoute,
    supplierCreateRoute,
    supplierEditRoute,
  ]),
])
