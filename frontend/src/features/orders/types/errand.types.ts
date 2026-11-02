export const ERRAND_STATUSES = [
  'CREATING',
  'CREATE_FAILED',
  'OPEN',
  'ACCEPTED',
  'PICKED_UP',
  'COMPLETED',
  'CANCELLED',
  'EXPIRED',
] as const
export type ErrandStatus = (typeof ERRAND_STATUSES)[number]
export type OrderAction = 'accept' | 'pickup' | 'complete' | 'cancel'
export type OrderUser = { id: string; name: string | null }
export type CreditState =
  'RESERVED' | 'SETTLEMENT_PENDING' | 'SETTLED' | 'RELEASE_PENDING' | 'RELEASED'
export type Errand = {
  id: string
  requester: OrderUser
  courier: OrderUser | null
  supplierId: string
  pickup: { name: string; address: string }
  deliveryLocation: string
  description: string
  notes: string
  creditReward: number
  status: ErrandStatus
  creditState: CreditState | null
  creationFailureCode: string | null
  createdAt: string
  updatedAt: string
  expiresAt: string
  acceptedAt: string | null
  pickedUpAt: string | null
  endedAt: string | null
  createRequestId: string | null
}
export type OrderErrorResponse = {
  code: string
  message: string
  errandId?: string
}
export type OrderPage = {
  items: Array<Errand>
  total: number
  page: number
  size: number
}
export type CreateOrderRequest = {
  createRequestId: string
  supplierId: string
  deliveryLocation: string
  description: string
  notes: string
  creditReward: number
  expiryMinutes: number
}
export type CreateOrderResponse = {
  errandId: string
  status: ErrandStatus
  creationFailureCode: string | null
  queryUrl: string
}
export type OrderActionResponse = {
  errandId: string
  status: ErrandStatus
  creditState: CreditState | null
}
export type MineParams = {
  as: 'all' | 'requester' | 'courier'
  view: 'all' | 'active' | 'history'
  page: number
  size: number
}
