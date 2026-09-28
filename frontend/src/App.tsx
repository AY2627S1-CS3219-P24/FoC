import {
  createRouter,
  ErrorComponent,
  RouterProvider,
} from '@tanstack/react-router'
import { QueryClientProvider } from '@tanstack/react-query'
import { useEffect } from 'react'

import { queryClient } from '#/lib/queryClient'
import { AuthProvider, useAuth } from '#/features/auth/providers/AuthProvider'
import { routeTree } from '#/routes'

const router = createRouter({
  routeTree,
  context: { auth: undefined! },
  defaultPreload: 'intent',
  scrollRestoration: true,
  defaultErrorComponent: ({ error }) => <ErrorComponent error={error} />,
})

// Supply React-owned operations to beforeLoad without calling hooks in the guard.
export const AuthRouterProvider = ({
  router: appRouter,
}: {
  router: typeof router
}) => {
  const auth = useAuth()
  useEffect(() => {
    if (auth.invalidation > 0) void appRouter.invalidate()
  }, [appRouter, auth.invalidation])

  return <RouterProvider router={appRouter} context={{ auth }} />
}

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router
  }
}

export const App = () => {
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <AuthRouterProvider router={router} />
      </AuthProvider>
    </QueryClientProvider>
  )
}
