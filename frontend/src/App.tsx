import {
  createRouter,
  ErrorComponent,
  RouterProvider,
} from '@tanstack/react-router'
import { QueryClientProvider } from '@tanstack/react-query'

import { queryClient } from '#/lib/queryClient'
import { axiosClient } from '#/lib/axiosClient'
import { setupAuthInterceptors } from '#/features/auth/lib/authInterceptors'
import { routeTree } from '#/routes'

const router = createRouter({
  routeTree,
  defaultPreload: 'intent',
  scrollRestoration: true,
  defaultErrorComponent: ({ error }) => <ErrorComponent error={error} />,
})

const removeAuthInterceptors = setupAuthInterceptors(axiosClient, () => {
  void router.navigate({ to: '/login', replace: true })
})

if (import.meta.hot) import.meta.hot.dispose(removeAuthInterceptors)

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router
  }
}

export const App = () => {
  return (
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  )
}
