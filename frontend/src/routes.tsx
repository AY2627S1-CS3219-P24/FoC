import { createRootRoute, createRoute } from '@tanstack/react-router'
import { HomePage } from '#/pages/Home/HomePage'

const rootRoute = createRootRoute()

const homeRoute = createRoute({
  getParentRoute: () => rootRoute,
  component: HomePage,
  path: '/',
})

export const routeTree = rootRoute.addChildren([homeRoute])
