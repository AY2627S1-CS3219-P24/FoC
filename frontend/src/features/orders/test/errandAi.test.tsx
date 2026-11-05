import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ErrandForm } from '../components/ErrandForm/ErrandForm'
import { aiChatResponseSchema } from '../schemas/errandAi.schema'
import * as api from '../api/errandAi.api'
import { setAccessToken } from '#/features/auth/lib/accessTokenStore'
import type { AiChatResponse } from '../schemas/errandAi.schema'
import type { CreateErrandValues } from '../schemas/createErrand.schema'

vi.mock('../api/errandAi.api', () => ({
  getErrandAiCapabilities: vi.fn(),
  chatWithErrandAi: vi.fn(),
  transcribeErrandAudio: vi.fn(),
}))
vi.mock('#/features/suppliers/hooks/usePickupLocations', () => ({
  usePickupLocations: () => ({ data: [], isSuccess: true }),
}))
const pickup = {
  id: '8e1f0000-0000-4000-8000-000000000002',
  name: 'Campus Cafe',
  address: 'COM1',
}
const initial: CreateErrandValues = {
  pickup: null,
  deliveryLocation: '',
  description: '',
  notes: '',
  creditReward: 5,
  expiryMinutes: 60,
}
const result: AiChatResponse = {
  message: 'Ready to review with 10 credits and 2 hours.',
  draft: {
    pickup,
    deliveryLocation: 'COM3',
    description: 'Coffee',
    notes: 'No sugar',
    creditReward: 10,
    expiryMinutes: 120,
  },
  missingFields: [],
  supplierChoices: [],
}
const clients: Array<QueryClient> = []
function setup(values = initial) {
  const client = new QueryClient({
    defaultOptions: { mutations: { retry: false } },
  })
  clients.push(client)
  const onReview = vi.fn()
  const view = render(
    <QueryClientProvider client={client}>
      <ErrandForm values={values} onReview={onReview} />
    </QueryClientProvider>,
  )
  return { ...view, onReview, user: userEvent.setup() }
}
beforeEach(() => {
  vi.resetAllMocks()
  Element.prototype.scrollIntoView = vi.fn()
  setAccessToken(
    `header.${btoa(JSON.stringify({ sub: 'requester', roles: ['USER'] }))}.signature`,
  )
  vi.mocked(api.chatWithErrandAi).mockResolvedValue(result)
  vi.mocked(api.getErrandAiCapabilities).mockResolvedValue({
    chatEnabled: true,
    voiceEnabled: true,
  })
})
afterEach(() => {
  clients.forEach((client) => client.clear())
  clients.length = 0
  setAccessToken(null)
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})
async function chat(
  user: ReturnType<typeof userEvent.setup>,
  text = 'Coffee to COM3',
) {
  await user.click(
    await screen.findByRole('button', { name: 'Create with AI' }),
  )
  await user.type(
    screen.getByRole('textbox', { name: 'Message to AI assistant' }),
    text,
  )
  await user.click(screen.getByRole('button', { name: 'Send message' }))
  await screen.findByRole('button', { name: 'Fill into form' })
}
it('only fills on request and passes values to the existing review flow', async () => {
  const { user, onReview } = setup()
  await chat(user)
  expect(screen.getByLabelText('Delivery location *')).toHaveValue('')
  expect(onReview).not.toHaveBeenCalled()
  await user.click(screen.getByRole('button', { name: 'Fill into form' }))
  expect(screen.getByLabelText('Delivery location *')).toHaveValue('COM3')
  expect(screen.getByLabelText('Courier reward *')).toHaveValue(10)
  await user.click(screen.getByRole('button', { name: 'Continue' }))
  await waitFor(() =>
    expect(onReview).toHaveBeenCalledWith(result.draft, expect.anything()),
  )
})
it('starts with hand-entered values and preserves nulls while applying explicit clears', async () => {
  vi.mocked(api.chatWithErrandAi).mockResolvedValue({
    ...result,
    draft: {
      ...result.draft,
      pickup: null,
      deliveryLocation: '',
      description: null,
      notes: '',
      creditReward: null,
      expiryMinutes: null,
    },
    missingFields: ['pickup', 'deliveryLocation'],
  })
  const { user, onReview } = setup({
    ...initial,
    pickup,
    deliveryLocation: 'Old delivery',
    description: 'Lunch',
    notes: 'Old notes',
  })
  await chat(user, 'Clear delivery and notes')
  expect(
    vi.mocked(api.chatWithErrandAi).mock.calls[0][0].draft.description,
  ).toBe('Lunch')
  await user.click(screen.getByRole('button', { name: 'Fill into form' }))
  expect(screen.getByLabelText('Delivery location *')).toHaveValue('')
  expect(screen.getByLabelText('Notes (optional)')).toHaveValue('')
  expect(screen.getByLabelText('What should be collected? *')).toHaveValue(
    'Lunch',
  )
  await user.click(screen.getByRole('button', { name: 'Continue' }))
  await screen.findByText('Enter a delivery location.')
  expect(onReview).not.toHaveBeenCalled()
})
it('asks before overwriting hand edits and supports keeping them', async () => {
  const { user } = setup()
  await chat(user)
  await user.click(screen.getByRole('button', { name: 'Close AI assistant' }))
  await user.type(screen.getByLabelText('Delivery location *'), 'My edit')
  await user.click(
    await screen.findByRole('button', { name: 'Create with AI' }),
  )
  await user.click(screen.getByRole('button', { name: 'Fill into form' }))
  await user.click(screen.getByRole('button', { name: 'Keep my edits' }))
  expect(screen.getByLabelText('Delivery location *')).toHaveValue('My edit')
  await user.click(screen.getByRole('button', { name: 'Fill into form' }))
  await user.click(screen.getByRole('button', { name: 'Apply draft' }))
  expect(screen.getByLabelText('Delivery location *')).toHaveValue('COM3')
})
it('sends supplier selection through chat and retries without duplicate messages', async () => {
  vi.mocked(api.chatWithErrandAi)
    .mockResolvedValueOnce({
      ...result,
      supplierChoices: [pickup],
      draft: { ...result.draft, pickup: null },
    })
    .mockRejectedValueOnce(new Error('unavailable'))
    .mockResolvedValueOnce(result)
  const { user } = setup()
  await chat(user)
  await user.click(screen.getByRole('button', { name: /Campus Cafe/ }))
  await screen.findByRole('button', { name: 'Retry message' })
  const request = vi.mocked(api.chatWithErrandAi).mock.calls[1][0]
  expect(request.selectedSupplierId).toBe(pickup.id)
  expect(request.history).toHaveLength(2)
  expect(
    request.history.some((message) => message.content === 'Choose Campus Cafe'),
  ).toBe(false)
  await user.click(screen.getByRole('button', { name: 'Retry message' }))
  await waitFor(() => expect(api.chatWithErrandAi).toHaveBeenCalledTimes(3))
  expect(vi.mocked(api.chatWithErrandAi).mock.calls[2][0]).toEqual(request)
  expect(
    within(screen.getByRole('log')).getAllByText('Choose Campus Cafe'),
  ).toHaveLength(1)
})
it('keeps input on failure and aborts pending requests on unmount', async () => {
  vi.mocked(api.chatWithErrandAi).mockRejectedValueOnce(new Error('no key'))
  const { user, unmount } = setup()
  await user.click(
    await screen.findByRole('button', { name: 'Create with AI' }),
  )
  await user.type(
    screen.getByRole('textbox', { name: 'Message to AI assistant' }),
    'Coffee',
  )
  await user.click(screen.getByRole('button', { name: 'Send message' }))
  await screen.findByRole('button', { name: 'Retry message' })
  expect(
    screen.getByRole('textbox', { name: 'Message to AI assistant' }),
  ).toHaveValue('Coffee')
  let complete!: (value: AiChatResponse) => void
  vi.mocked(api.chatWithErrandAi).mockImplementation(
    () =>
      new Promise((resolve) => {
        complete = resolve
      }),
  )
  await user.click(screen.getByRole('button', { name: 'Retry message' }))
  const signal = vi.mocked(api.chatWithErrandAi).mock.calls[1][1]
  unmount()
  expect(signal.aborted).toBe(true)
  await act(async () => complete(result))
})
it('rejects AI drafts with negative rewards', () => {
  expect(
    aiChatResponseSchema.safeParse({
      ...result,
      draft: { ...result.draft, creditReward: -1 },
    }).success,
  ).toBe(false)
})
it('Enter in the assistant does not submit the errand form', async () => {
  const { user, onReview } = setup(result.draft as CreateErrandValues)
  await user.click(
    await screen.findByRole('button', { name: 'Create with AI' }),
  )
  const input = screen.getByRole('textbox', { name: 'Message to AI assistant' })
  fireEvent.change(input, { target: { value: 'Change notes' } })
  fireEvent.keyDown(input, { key: 'Enter' })
  await waitFor(() => expect(api.chatWithErrandAi).toHaveBeenCalledTimes(1))
  expect(onReview).not.toHaveBeenCalled()
})

function mockMicrophone(
  onStop: (deliver: () => void) => void = (deliver) => deliver(),
) {
  const stopTrack = vi.fn()
  const stream = { getTracks: () => [{ stop: stopTrack }] }
  class Recorder {
    static isTypeSupported = () => true
    state = 'inactive'
    mimeType = 'audio/webm'
    ondataavailable?: (event: { data: Blob }) => void
    onstop?: () => void
    start() {
      this.state = 'recording'
    }
    stop() {
      this.state = 'inactive'
      onStop(() => {
        this.ondataavailable?.({ data: new Blob(['audio']) })
        this.onstop?.()
      })
    }
  }
  vi.stubGlobal('MediaRecorder', Recorder)
  Object.defineProperty(navigator, 'mediaDevices', {
    configurable: true,
    value: { getUserMedia: vi.fn().mockResolvedValue(stream) },
  })
  return stopTrack
}
it('collapsing the panel cancels recording without uploading and preserves the composer', async () => {
  const stopTrack = mockMicrophone()
  const { user } = setup()
  await user.click(
    await screen.findByRole('button', { name: 'Create with AI' }),
  )
  await user.type(
    screen.getByRole('textbox', { name: 'Message to AI assistant' }),
    'Keep this',
  )
  await user.click(screen.getByRole('button', { name: 'Record voice message' }))
  await screen.findByRole('button', { name: 'Stop recording' })
  await user.click(screen.getByRole('button', { name: 'Close AI assistant' }))
  expect(stopTrack).toHaveBeenCalledTimes(1)
  expect(api.transcribeErrandAudio).not.toHaveBeenCalled()
  await user.click(
    await screen.findByRole('button', { name: 'Create with AI' }),
  )
  expect(
    screen.getByRole('textbox', { name: 'Message to AI assistant' }),
  ).toHaveValue('Keep this')
  expect(
    screen.queryByRole('button', { name: 'Stop recording' }),
  ).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: 'Record voice message' }))
  await screen.findByRole('button', { name: 'Stop recording' })
  await user.keyboard('{Escape}')
  expect(
    screen.queryByRole('dialog', { name: 'Create with AI' }),
  ).not.toBeInTheDocument()
  expect(stopTrack).toHaveBeenCalledTimes(2)
  expect(api.transcribeErrandAudio).not.toHaveBeenCalled()
})
it('keeps the composer locked through delayed audio delivery and transcription', async () => {
  let deliver!: () => void
  const stopTrack = mockMicrophone((finish) => {
    deliver = finish
  })
  let transcribe!: (text: string) => void
  vi.mocked(api.transcribeErrandAudio).mockReturnValue(
    new Promise((resolve) => {
      transcribe = resolve
    }),
  )
  const { user, onReview } = setup()
  await user.click(
    await screen.findByRole('button', { name: 'Create with AI' }),
  )
  await user.type(
    screen.getByRole('textbox', { name: 'Message to AI assistant' }),
    'Please',
  )
  await user.click(screen.getByRole('button', { name: 'Record voice message' }))
  await user.click(
    await screen.findByRole('button', { name: 'Stop recording' }),
  )
  expect(stopTrack).toHaveBeenCalledTimes(1)
  expect(
    screen.queryByRole('button', { name: 'Send message' }),
  ).not.toBeInTheDocument()
  expect(api.transcribeErrandAudio).not.toHaveBeenCalled()
  act(deliver)
  const send = screen.getByRole('button', { name: 'Send message' })
  expect(send).toBeDisabled()
  expect(
    screen.getByRole('textbox', { name: 'Message to AI assistant' }),
  ).toBeDisabled()
  await user.click(send)
  expect(api.transcribeErrandAudio).toHaveBeenCalledTimes(1)
  await act(async () => transcribe('Coffee to COM3'))
  await waitFor(() =>
    expect(
      screen.getByRole('textbox', { name: 'Message to AI assistant' }),
    ).toHaveValue('Please\nCoffee to COM3'),
  )
  expect(api.chatWithErrandAi).not.toHaveBeenCalled()
  expect(onReview).not.toHaveBeenCalled()
  expect(screen.getByLabelText('Delivery location *')).toHaveValue('')
  expect(
    screen.getByRole('textbox', { name: 'Message to AI assistant' }),
  ).toBeEnabled()
  expect(send).toBeEnabled()
  await user.click(send)
  expect(vi.mocked(api.chatWithErrandAi).mock.calls[0][0].message).toBe(
    'Please\nCoffee to COM3',
  )
})

it('discards the final audio when cancelled after stop but before delivery', async () => {
  let deliver!: () => void
  const stopTrack = mockMicrophone((finish) => {
    deliver = finish
  })
  const { user } = setup()
  await user.click(
    await screen.findByRole('button', { name: 'Create with AI' }),
  )
  await user.type(
    screen.getByRole('textbox', { name: 'Message to AI assistant' }),
    'Keep this',
  )
  await user.click(screen.getByRole('button', { name: 'Record voice message' }))
  await user.click(
    await screen.findByRole('button', { name: 'Stop recording' }),
  )
  await user.click(screen.getByRole('button', { name: 'Cancel' }))
  act(deliver)
  expect(stopTrack).toHaveBeenCalledTimes(1)
  expect(api.transcribeErrandAudio).not.toHaveBeenCalled()
  expect(
    screen.getByRole('textbox', { name: 'Message to AI assistant' }),
  ).toHaveValue('Keep this')
  expect(screen.getByRole('button', { name: 'Send message' })).toBeEnabled()
})

it('acknowledges form changes without changing the draft or bypassing overwrite confirmation', async () => {
  const { user } = setup()
  await chat(user)
  await user.click(screen.getByRole('button', { name: 'Close AI assistant' }))
  fireEvent.change(screen.getByLabelText('Delivery location *'), {
    target: { value: 'COM2' },
  })
  await user.click(
    await screen.findByRole('button', { name: 'Create with AI' }),
  )
  await user.click(
    screen.getByRole('button', { name: 'Continue current draft' }),
  )
  await user.click(screen.getByRole('button', { name: 'Close AI assistant' }))
  await user.click(
    await screen.findByRole('button', { name: 'Create with AI' }),
  )
  expect(
    screen.queryByRole('button', { name: 'Continue current draft' }),
  ).not.toBeInTheDocument()
  await user.type(
    screen.getByRole('textbox', { name: 'Message to AI assistant' }),
    'Reward 10',
  )
  await user.click(screen.getByRole('button', { name: 'Send message' }))
  await waitFor(() => expect(api.chatWithErrandAi).toHaveBeenCalledTimes(2))
  expect(
    vi.mocked(api.chatWithErrandAi).mock.calls[1][0].draft.deliveryLocation,
  ).toBe('COM3')
  await user.click(screen.getByRole('button', { name: 'Fill into form' }))
  await user.click(screen.getByRole('button', { name: 'Keep my edits' }))
  expect(screen.getByLabelText('Delivery location *')).toHaveValue('COM2')
  fireEvent.change(screen.getByLabelText('Delivery location *'), {
    target: { value: 'COM1' },
  })
  await user.type(
    screen.getByRole('textbox', { name: 'Message to AI assistant' }),
    'Next',
  )
  await user.click(screen.getByRole('button', { name: 'Send message' }))
  expect(
    screen.getByRole('button', { name: 'Continue current draft' }),
  ).toBeInTheDocument()
  expect(api.chatWithErrandAi).toHaveBeenCalledTimes(2)
})

it('detects edits while open and restarts from the latest form without losing unsent text', async () => {
  const { user } = setup()
  await chat(user)
  const input = screen.getByRole('textbox', { name: 'Message to AI assistant' })
  await user.type(input, 'Reward 10')
  fireEvent.change(screen.getByLabelText('Delivery location *'), {
    target: { value: 'COM2' },
  })
  await user.click(screen.getByRole('button', { name: 'Send message' }))
  expect(api.chatWithErrandAi).toHaveBeenCalledTimes(1)
  await user.click(screen.getByRole('button', { name: 'Restart from form' }))
  expect(input).toHaveValue('Reward 10')
  expect(screen.getByRole('log')).toBeEmptyDOMElement()
  expect(
    screen.queryByRole('button', { name: 'Fill into form' }),
  ).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: 'Send message' }))
  await waitFor(() => expect(api.chatWithErrandAi).toHaveBeenCalledTimes(2))
  expect(vi.mocked(api.chatWithErrandAi).mock.calls[1][0]).toMatchObject({
    message: 'Reward 10',
    history: [],
    draft: { deliveryLocation: 'COM2' },
  })
  await user.click(screen.getByRole('button', { name: 'Fill into form' }))
  expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
})

it('checks form changes before supplier selection and retry, and clears failed requests on restart', async () => {
  vi.mocked(api.chatWithErrandAi)
    .mockResolvedValueOnce({ ...result, supplierChoices: [pickup] })
    .mockRejectedValueOnce(new Error('unavailable'))
    .mockResolvedValue(result)
  const { user } = setup()
  await chat(user)
  fireEvent.change(screen.getByLabelText('Delivery location *'), {
    target: { value: 'COM2' },
  })
  await user.click(screen.getByRole('button', { name: /Campus Cafe/ }))
  expect(api.chatWithErrandAi).toHaveBeenCalledTimes(1)
  await user.click(
    screen.getByRole('button', { name: 'Continue current draft' }),
  )
  await user.click(screen.getByRole('button', { name: /Campus Cafe/ }))
  await screen.findByRole('button', { name: 'Retry message' })
  fireEvent.change(screen.getByLabelText('Delivery location *'), {
    target: { value: 'COM1' },
  })
  await user.click(screen.getByRole('button', { name: 'Retry message' }))
  expect(api.chatWithErrandAi).toHaveBeenCalledTimes(2)
  await user.click(screen.getByRole('button', { name: 'Restart from form' }))
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  expect(
    screen.queryByRole('button', { name: 'Retry message' }),
  ).not.toBeInTheDocument()
  await user.type(
    screen.getByRole('textbox', { name: 'Message to AI assistant' }),
    'Coffee',
  )
  await user.click(screen.getByRole('button', { name: 'Send message' }))
  await waitFor(() => expect(api.chatWithErrandAi).toHaveBeenCalledTimes(3))
  expect(vi.mocked(api.chatWithErrandAi).mock.calls[2][0]).toMatchObject({
    history: [],
    draft: { deliveryLocation: 'COM1' },
    selectedSupplierId: undefined,
  })
})

it('prevents restarting during an outstanding chat request', async () => {
  let complete!: (value: AiChatResponse) => void
  vi.mocked(api.chatWithErrandAi).mockImplementation(
    () =>
      new Promise((resolve) => {
        complete = resolve
      }),
  )
  const { user } = setup()
  await user.click(
    await screen.findByRole('button', { name: 'Create with AI' }),
  )
  await user.type(
    screen.getByRole('textbox', { name: 'Message to AI assistant' }),
    'Coffee',
  )
  await user.click(screen.getByRole('button', { name: 'Send message' }))
  await user.click(screen.getByRole('button', { name: 'Close AI assistant' }))
  fireEvent.change(screen.getByLabelText('Delivery location *'), {
    target: { value: 'COM2' },
  })
  await user.click(
    await screen.findByRole('button', { name: 'Create with AI' }),
  )
  expect(
    screen.getByRole('button', { name: 'Restart from form' }),
  ).toBeDisabled()
  await act(async () => complete(result))
  await user.click(screen.getByRole('button', { name: 'Restart from form' }))
  expect(screen.getByRole('log')).toBeEmptyDOMElement()
})

it('prevents restarting while requesting a microphone, recording, or transcribing', async () => {
  const stopTrack = mockMicrophone()
  let grant!: (stream: MediaStream) => void
  vi.mocked(navigator.mediaDevices.getUserMedia).mockImplementation(
    () =>
      new Promise((resolve) => {
        grant = resolve
      }),
  )
  let transcribe!: (text: string) => void
  vi.mocked(api.transcribeErrandAudio).mockImplementation(
    () =>
      new Promise((resolve) => {
        transcribe = resolve
      }),
  )
  const { user } = setup()
  await chat(user)
  await user.type(
    screen.getByRole('textbox', { name: 'Message to AI assistant' }),
    'Next',
  )
  fireEvent.change(screen.getByLabelText('Delivery location *'), {
    target: { value: 'COM2' },
  })
  await user.click(screen.getByRole('button', { name: 'Send message' }))
  await user.click(screen.getByRole('button', { name: 'Record voice message' }))
  expect(
    screen.getByRole('textbox', { name: 'Message to AI assistant' }),
  ).toBeDisabled()
  expect(
    screen.getByRole('button', { name: 'Restart from form' }),
  ).toBeDisabled()
  await act(async () =>
    grant({ getTracks: () => [{ stop: stopTrack }] } as unknown as MediaStream),
  )
  expect(
    screen.getByRole('button', { name: 'Restart from form' }),
  ).toBeDisabled()
  await user.click(screen.getByRole('button', { name: 'Stop recording' }))
  expect(
    screen.getByRole('button', { name: 'Restart from form' }),
  ).toBeDisabled()
  await act(async () => transcribe('Coffee'))
  await user.click(screen.getByRole('button', { name: 'Restart from form' }))
  expect(
    screen.getByRole('textbox', { name: 'Message to AI assistant' }),
  ).toHaveValue('Next\nCoffee')
  expect(stopTrack).toHaveBeenCalledTimes(1)
})

it('offers text-only chat when voice is disabled and queries once per form visit', async () => {
  vi.mocked(api.getErrandAiCapabilities).mockResolvedValue({
    chatEnabled: true,
    voiceEnabled: false,
  })
  const { user, unmount } = setup()
  await chat(user)
  expect(
    screen.queryByRole('button', { name: 'Record voice message' }),
  ).not.toBeInTheDocument()
  expect(
    screen.queryByText('Voice input becomes editable text.'),
  ).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: 'Close AI assistant' }))
  await user.click(
    await screen.findByRole('button', { name: 'Create with AI' }),
  )
  expect(api.getErrandAiCapabilities).toHaveBeenCalledTimes(1)
  unmount()
  setup()
  await screen.findByRole('button', { name: 'Create with AI' })
  expect(api.getErrandAiCapabilities).toHaveBeenCalledTimes(2)
})

it.each(['disabled', 'failed'] as const)(
  'keeps manual review available when AI is %s',
  async (mode) => {
    if (mode === 'failed')
      vi.mocked(api.getErrandAiCapabilities).mockRejectedValue(
        new Error('offline'),
      )
    else
      vi.mocked(api.getErrandAiCapabilities).mockResolvedValue({
        chatEnabled: false,
        voiceEnabled: false,
      })
    const { user, onReview } = setup(result.draft as CreateErrandValues)
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    await waitFor(() => expect(onReview).toHaveBeenCalled())
    expect(
      screen.queryByRole('button', { name: 'Create with AI' }),
    ).not.toBeInTheDocument()
    expect(api.getErrandAiCapabilities).toHaveBeenCalledTimes(1)
    expect(api.chatWithErrandAi).not.toHaveBeenCalled()
  },
)

it('hides the launcher while loading and discards chat state when the user changes', async () => {
  let complete!: (flags: {
    chatEnabled: boolean
    voiceEnabled: boolean
  }) => void
  vi.mocked(api.getErrandAiCapabilities).mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        complete = resolve
      }),
  )
  const { user } = setup()
  expect(
    screen.queryByRole('button', { name: 'Create with AI' }),
  ).not.toBeInTheDocument()
  await act(async () => complete({ chatEnabled: true, voiceEnabled: true }))
  await chat(user)
  act(() =>
    setAccessToken(
      `header.${btoa(JSON.stringify({ sub: 'another-user', roles: ['USER'] }))}.signature`,
    ),
  )
  await user.click(
    await screen.findByRole('button', { name: 'Create with AI' }),
  )
  expect(screen.getByRole('log')).toBeEmptyDOMElement()
  expect(api.getErrandAiCapabilities).toHaveBeenCalledTimes(2)
})
