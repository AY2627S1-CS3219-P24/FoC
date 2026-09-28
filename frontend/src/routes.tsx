import {
  createRootRouteWithContext,
  createRoute,
  redirect,
  Outlet,
} from '@tanstack/react-router'

import { AuthLayout } from '#/features/auth/layouts/AuthLayout/AuthLayout'
import { LoginPage } from '#/features/auth/pages/LoginPage/LoginPage'
import { RegisterPage } from '#/features/auth/pages/RegisterPage/RegisterPage'
import { AppHomePage } from '#/pages/AppHomePage/AppHomePage'
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
  pendingComponent: SessionRecoveryPending,
  errorComponent: SessionRecoveryError,
})

const appRoute = createRoute({
  getParentRoute: () => protectedRoute,
  path: '/app',
  component: AppHomePage,
})

export const routeTree = rootRoute.addChildren([
  indexRoute,
  protectedRoute.addChildren([appRoute]),
  authLayoutRoute.addChildren([loginRoute, registerRoute]),
])
