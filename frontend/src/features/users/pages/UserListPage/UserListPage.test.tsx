import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  createMemoryHistory,
  createRouter,
  RouterProvider,
} from '@tanstack/react-router'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, test, vi } from 'vitest'
import { axiosClient } from '#/lib/axiosClient'
import { routeTree } from '#/routes'
import type { AuthOperations } from '#/features/auth/providers/AuthProvider'
import type { User, UserListParams, UserPage } from '../../types/user.types'

const alex: User = {
  id: 'user-1',
  name: 'Alex Tan',
  email: 'alex@example.com',
  roles: ['USER'],
}
const blair: User = {
  id: 'user-2',
  name: 'Blair Lee',
  email: 'blair@example.com',
  roles: ['ADMIN'],
}

const renderListPage = (
  getPage: (params: UserListParams) => UserPage | Promise<UserPage>,
  initialEntry = '/admin/users',
) => {
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
  const get = vi
    .spyOn(axiosClient, 'get')
    .mockImplementation(async (url, config) => {
      if (url === '/users/user-1') return { data: alex }
      if (url !== '/users') throw new Error(`Unexpected request: ${url}`)
      return { data: await getPage(config?.params as UserListParams) }
    })
  const router = createRouter({
    routeTree,
    context: {
      auth: {
        ensureAuthenticated: async () => true,
        isAdmin: () => true,
        completeLogin: () => {},
        logout: async () => {},
      } satisfies AuthOperations,
    },
    history: createMemoryHistory({ initialEntries: [initialEntry] }),
  })
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const userEvents = userEvent.setup()

  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )

  return { get, router, userEvents }
}

afterEach(() => vi.restoreAllMocks())

test('keeps the table shape while the first page loads', async () => {
  let releasePage: ((page: UserPage) => void) | undefined
  renderListPage(
    () =>
      new Promise<UserPage>((resolve) => {
        releasePage = resolve
      }),
  )

  const table = await screen.findByRole('table')
  expect(table).toHaveAttribute('aria-busy', 'true')
  expect(screen.queryByText('Loading users…')).not.toBeInTheDocument()

  await act(async () =>
    releasePage?.({ items: [alex], total: 1, page: 0, size: 20 }),
  )

  expect(await screen.findByText('Alex Tan')).toBeVisible()
  expect(table).toHaveAttribute('aria-busy', 'false')
})

test('paginates, sorts, and filters users through server parameters', async () => {
  const { get, userEvents } = renderListPage((params) => ({
    items: [params.page === 1 ? blair : alex],
    total: 21,
    page: params.page,
    size: 20,
  }))

  expect(await screen.findByText('Alex Tan')).toBeVisible()
  await userEvents.click(screen.getByRole('button', { name: 'Next Page' }))
  expect(await screen.findByText('Blair Lee')).toBeVisible()
  expect(get).toHaveBeenCalledWith('/users', {
    params: expect.objectContaining({ page: 1, sort: 'name,asc' }),
  })

  await userEvents.click(screen.getByRole('button', { name: 'Name' }))
  await waitFor(() =>
    expect(get).toHaveBeenCalledWith('/users', {
      params: expect.objectContaining({ page: 0, sort: 'name,desc' }),
    }),
  )

  await userEvents.type(
    screen.getByRole('searchbox', { name: 'Search users by name or email' }),
    'Alex',
  )
  await waitFor(() =>
    expect(get).toHaveBeenCalledWith('/users', {
      params: expect.objectContaining({ page: 0, search: 'Alex' }),
    }),
  )

  await userEvents.selectOptions(screen.getByLabelText('Role'), 'ADMIN')
  await waitFor(() =>
    expect(get).toHaveBeenCalledWith('/users', {
      params: expect.objectContaining({
        page: 0,
        search: 'Alex',
        role: 'ADMIN',
      }),
    }),
  )
})

test('returns to the last valid page when the result total shrinks', async () => {
  let total = 21
  const { get, userEvents } = renderListPage((params) => {
    if (params.page === 1) total = 1
    return {
      items: params.page === 0 ? [alex] : [],
      total,
      page: params.page,
      size: 20,
    }
  })

  expect(await screen.findByText('Alex Tan')).toBeVisible()
  await userEvents.click(screen.getByRole('button', { name: 'Next Page' }))
  await waitFor(() =>
    expect(get).toHaveBeenCalledWith('/users', {
      params: expect.objectContaining({ page: 1 }),
    }),
  )
  await waitFor(() =>
    expect(screen.getByRole('button', { name: 'Next Page' })).toBeDisabled(),
  )
})
