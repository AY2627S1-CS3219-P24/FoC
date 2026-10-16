import type { CreateErrandValues } from '../schemas/createErrand.schema'
import type {
  Errand,
  OrderAction,
  OrderUser,
  PickupLocation,
} from '../types/errand.types'

export const samplePickups: Array<PickupLocation> = [
  {
    id: 'sample-coffee',
    name: 'CoffeeBean @ COM3',
    address: 'COM3 · Level 1 collection counter',
  },
  {
    id: 'sample-library',
    name: 'Central Library',
    address: 'Central Library · Level 1',
  },
  { id: 'sample-utown', name: 'UTown', address: 'UTown · Town Plaza' },
]
export const seedOrders = (
  user: OrderUser,
  now = Date.now(),
): Array<Errand> => {
  const other = { id: 'sample-jamie', name: 'Jamie' }
  const ago = (minutes: number) => new Date(now - minutes * 60000).toISOString()
  const base: Errand = {
    id: '1001',
    requester: user,
    courier: null,
    pickup: samplePickups[0],
    delivery: 'COM2 · Level 1 lobby',
    description: '1 iced latte, less sugar',
    notes: 'Meet by the main entrance.',
    reward: 5,
    status: 'OPEN',
    creditState: 'RESERVED',
    createdAt: ago(12),
    expiresAt: ago(-48),
  }
  return [
    base,
    {
      ...base,
      id: '1002',
      requester: other,
      pickup: samplePickups[1],
      delivery: 'UTown · Town Plaza',
      description: 'Collect 20 printed pages',
      notes: 'The pages are at the collection desk.',
      reward: 3,
    },
    {
      ...base,
      id: '1003',
      requester: other,
      courier: user,
      status: 'ACCEPTED',
      acceptedAt: ago(8),
    },
    {
      ...base,
      id: '1004',
      courier: other,
      status: 'PICKED_UP',
      acceptedAt: ago(8),
      pickedUpAt: ago(2),
    },
    {
      ...base,
      id: '1005',
      requester: other,
      courier: user,
      status: 'PICKED_UP',
      acceptedAt: ago(8),
      pickedUpAt: ago(2),
    },
    {
      ...base,
      id: '1006',
      courier: other,
      status: 'COMPLETED',
      creditState: 'SETTLED',
      createdAt: ago(100),
      acceptedAt: ago(90),
      pickedUpAt: ago(75),
      endedAt: ago(60),
    },
    {
      ...base,
      id: '1007',
      status: 'CANCELLED',
      creditState: 'RELEASED',
      endedAt: ago(5),
    },
    {
      ...base,
      id: '1008',
      status: 'EXPIRED',
      creditState: 'RELEASED',
      createdAt: ago(100),
      expiresAt: ago(40),
      endedAt: ago(40),
    },
  ]
}

// One temporary preview list. No balances, settlement, timers or backend rules.
let currentUserId: string | undefined
let orders: Array<Errand> = []
export const previewOrders = (user: OrderUser) => {
  if (currentUserId !== user.id) {
    currentUserId = user.id
    orders = seedOrders(user)
  }
  return orders
}
export const addPreviewOrder = (
  user: OrderUser,
  values: CreateErrandValues,
) => {
  if (!values.pickup) throw new Error('Choose a pickup location.')
  const order: Errand = {
    id: crypto.randomUUID(),
    requester: user,
    courier: null,
    pickup: values.pickup,
    delivery: values.delivery,
    description: values.description,
    notes: values.notes,
    reward: values.reward,
    status: 'OPEN',
    creditState: 'RESERVED',
    createdAt: new Date().toISOString(),
    expiresAt: new Date(
      Date.now() + values.expiryMinutes * 60000,
    ).toISOString(),
  }
  previewOrders(user).unshift(order)
  return structuredClone(order)
}
export const updatePreviewOrder = (
  user: OrderUser,
  id: string,
  action: OrderAction,
) => {
  const list = previewOrders(user)
  const index = list.findIndex((order) => order.id === id)
  if (index < 0) throw new Error('This errand could not be found.')
  const at = new Date().toISOString()
  const changes: Record<OrderAction, Partial<Errand>> = {
    accept: { status: 'ACCEPTED', courier: user, acceptedAt: at },
    pickup: { status: 'PICKED_UP', pickedUpAt: at },
    complete: {
      status: 'COMPLETED',
      endedAt: at,
      creditState: 'SETTLEMENT_PENDING',
    },
    cancel: {
      status: 'CANCELLED',
      endedAt: at,
      creditState: 'RELEASE_PENDING',
    },
  }
  list[index] = { ...list[index], ...changes[action] }
  return structuredClone(list[index])
}
