import { createRootRoute, createRoute } from '@tanstack/react-router'
import { HomePage } from '#/pages/Home/HomePage'
import { AuthLayout } from '#/features/auth/layouts/AuthLayout'
import { LoginPage } from '#/features/auth/pages/LoginPage'
import { RegisterPage } from '#/features/auth/pages/RegisterPage'

const rootRoute = createRootRoute()

const homeRoute = createRoute({
  getParentRoute: () => rootRoute,
  component: HomePage,
  path: '/',
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

export const routeTree = rootRoute.addChildren([
  homeRoute,
  authLayoutRoute.addChildren([loginRoute, registerRoute]),
])
