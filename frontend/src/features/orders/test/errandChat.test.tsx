import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { setAccessToken } from '#/features/auth/lib/accessTokenStore'
import { connectRealtime } from '#/lib/realtime'
import * as api from '../api/orders.api'
import { ErrandChat } from '../components/ErrandChat/ErrandChat'
import { orderFixture } from './orderFixtures'
import type { Errand, ErrandMessage } from '../types/errand.types'

vi.mock('#/lib/realtime', () => ({ connectRealtime: vi.fn() }))
vi.mock('../api/orders.api', () => ({
  getChat: vi.fn(),
  sendMessage: vi.fn(),
}))

const handlers = new Map<string, (payload?: unknown) => void>()
const socket = {
  on: vi.fn((event: string, handler: (payload?: unknown) => void) =>
    handlers.set(event, handler),
  ),
  off: vi.fn(),
}
const push = (event: string, payload?: unknown) =>
  act(() => handlers.get(event)?.(payload))

const accepted = (overrides: Partial<Errand> = {}) =>
  orderFixture({
    status: 'ACCEPTED',
    courier: { id: 'courier', name: 'Sam' },
    ...overrides,
  })
const at = (minute: number) =>
  new Date(Date.UTC(2030, 0, 1, 8, minute)).toISOString()
const message = (
  id: string,
  senderId: string,
  body: string,
  minute = 0,
): ErrandMessage => ({
  id,
  errandId: accepted().id,
  senderId,
  body,
  sentAt: at(minute),
})
const chat = (messages: Array<ErrandMessage>) => ({ messages })
const signIn = (userId: string, roles = ['USER']) =>
  setAccessToken(
    `header.${btoa(JSON.stringify({ sub: userId, roles }))}.signature`,
  )
const setup = (order: Errand) => {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const view = render(
    <QueryClientProvider client={client}>
      <ErrandChat order={order} />
    </QueryClientProvider>,
  )
  return { ...view, client, user: userEvent.setup() }
}

beforeEach(() => {
  handlers.clear()
  vi.mocked(connectRealtime).mockReturnValue(socket as never)
  vi.mocked(api.getChat).mockResolvedValue(
    chat([message('m1', 'courier', 'On my way to the cafe', 1)]),
  )
  signIn('requester')
})
afterEach(() => {
  cleanup()
  setAccessToken(null)
  vi.clearAllMocks()
  vi.restoreAllMocks()
})

it('shows the conversation and sends with Enter', async () => {
  vi.mocked(api.sendMessage).mockImplementation(async (_errand, sent) =>
    message(sent.id, 'requester', sent.body, 2),
  )
  const { user } = setup(accepted())
  expect(
    screen.getByRole('heading', { name: 'Chat with Sam' }),
  ).toBeInTheDocument()
  await screen.findByText('On my way to the cafe')

  const box = screen.getByRole('textbox', { name: 'Message' })
  await user.type(box, '  Less ice please  {Enter}')

  expect(api.sendMessage).toHaveBeenCalledExactlyOnceWith(accepted().id, {
    id: expect.stringMatching(/^[0-9a-f-]{36}$/),
    body: 'Less ice please',
  })
  const mine = await screen.findByText('Less ice please')
  expect(mine.closest('[data-mine]')).toHaveAttribute('data-mine', 'true')
  expect(box).toHaveValue('')
})

it('does not send on Shift+Enter or while an input method is composing', async () => {
  const { user } = setup(accepted())
  const box = await screen.findByRole('textbox', { name: 'Message' })
  await user.type(box, 'line one{Shift>}{Enter}{/Shift}line two')
  expect(box).toHaveValue('line one\nline two')
  fireEvent.keyDown(box, { key: 'Enter', isComposing: true })
  expect(api.sendMessage).not.toHaveBeenCalled()
})

it('shows messages pushed by the realtime service, once each', async () => {
  setup(accepted())
  await screen.findByText('On my way to the cafe')
  expect(connectRealtime).toHaveBeenCalledOnce()

  const pushed = {
    id: 'm2',
    errandId: accepted().id,
    senderId: 'courier',
    body: 'Queue is long',
    sentAt: at(3),
  }
  push('errand.message', pushed)
  push('errand.message', pushed)
  push('errand.message', {
    ...pushed,
    id: 'm3',
    errandId: 'other',
    body: 'Wrong chat',
  })

  await screen.findByText('Queue is long')
  expect(screen.getAllByText('Queue is long')).toHaveLength(1)
  expect(screen.queryByText('Wrong chat')).not.toBeInTheDocument()
})

it('keeps a failed message with a Retry that resends the same message', async () => {
  vi.mocked(api.sendMessage)
    .mockRejectedValueOnce(new Error('offline'))
    .mockImplementationOnce(async (_errand, sent) =>
      message(sent.id, 'requester', sent.body, 2),
    )
  const { user } = setup(accepted())
  await user.type(
    await screen.findByRole('textbox', { name: 'Message' }),
    'Are you close?{Enter}',
  )
  await screen.findByText(/Not sent/)
  await user.click(screen.getByRole('button', { name: 'Retry' }))

  await vi.waitFor(() =>
    expect(screen.queryByText(/Not sent/)).not.toBeInTheDocument(),
  )
  const [first, second] = vi.mocked(api.sendMessage).mock.calls
  expect(second[1]).toEqual(first[1])
  expect(screen.getAllByText('Are you close?')).toHaveLength(1)
})

it('says so when the live connection drops, and catches up when it is back', async () => {
  setup(accepted())
  await screen.findByText('On my way to the cafe')
  push('disconnect')
  expect(await screen.findByText(/Reconnecting/)).toBeInTheDocument()
  vi.mocked(api.getChat).mockClear()
  push('connect')
  await vi.waitFor(() =>
    expect(screen.queryByText(/Reconnecting/)).not.toBeInTheDocument(),
  )
  expect(api.getChat).toHaveBeenCalled()
})

it('is read-only after the requester confirms receipt', async () => {
  setup(accepted({ status: 'COMPLETED' }))
  await screen.findByText('On my way to the cafe')
  expect(
    screen.queryByRole('textbox', { name: 'Message' }),
  ).not.toBeInTheDocument()
  expect(
    screen.getByText('Errand ended, this chat is closed for view only'),
  ).toBeInTheDocument()
  expect(connectRealtime).not.toHaveBeenCalled()
})

it('lets admins read the conversation without joining it', async () => {
  signIn('admin', ['ADMIN'])
  setup(accepted())
  await screen.findByText('On my way to the cafe')
  expect(
    screen.getByRole('heading', { name: 'Chat between Alex and Sam' }),
  ).toBeInTheDocument()
  expect(
    screen.queryByRole('textbox', { name: 'Message' }),
  ).not.toBeInTheDocument()
  expect(connectRealtime).not.toHaveBeenCalled()
})

it('stays hidden until a courier has accepted', () => {
  setup(orderFixture())
  expect(screen.queryByRole('region', { name: 'Chat' })).not.toBeInTheDocument()
  expect(api.getChat).not.toHaveBeenCalled()
})
