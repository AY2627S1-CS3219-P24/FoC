import { createRootRoute, createRoute, redirect } from '@tanstack/react-router'

import { AuthLayout } from '#/features/auth/layouts/AuthLayout'
import { LoginPage } from '#/features/auth/pages/LoginPage/LoginPage'
import { RegisterPage } from '#/features/auth/pages/RegisterPage/RegisterPage'
import { AppHomePage } from '#/pages/AppHomePage/AppHomePage'
import { ensureSession, isLoggingOut } from '#/features/auth/lib/authSession'
import { ProtectedSessionBoundary } from '#/features/auth/layouts/ProtectedSessionBoundary'
import {
  SessionRecoveryPending,
  SessionRecoveryError,
} from '#/features/auth/components/SessionRecoveryFeedback'

const rootRoute = createRootRoute()

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
  component: ProtectedSessionBoundary,
  beforeLoad: async () => {
    if (isLoggingOut()) return
    const session = await ensureSession()
    if (isLoggingOut()) return
    if (!session) throw redirect({ to: '/login', replace: true })
  },
  pendingMs: 0,
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
