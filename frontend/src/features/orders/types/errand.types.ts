export const ERRAND_STATUSES = [
  'OPEN',
  'ACCEPTED',
  'PICKED_UP',
  'COMPLETED',
  'CANCELLED',
  'EXPIRED',
] as const
export type ErrandStatus = (typeof ERRAND_STATUSES)[number]
export type OrderAction = 'accept' | 'pickup' | 'complete' | 'cancel'
export type OrderUser = { id: string; name: string }
export type PickupLocation = { id: string; name: string; address: string }
export type CreditState =
  'RESERVED' | 'SETTLEMENT_PENDING' | 'SETTLED' | 'RELEASE_PENDING' | 'RELEASED'
export type Errand = {
  id: string
  requester: OrderUser
  courier: OrderUser | null
  pickup: PickupLocation
  delivery: string
  description: string
  notes: string
  reward: number
  status: ErrandStatus
  creditState: CreditState
  createdAt: string
  expiresAt: string
  acceptedAt?: string
  pickedUpAt?: string
  endedAt?: string
}
export type OrderListSearch = {
  tab: 'active' | 'history'
  participation: 'all' | 'requested' | 'delivering'
}
export const parseOrderListSearch = (
  search: Record<string, unknown>,
): OrderListSearch => ({
  tab: search.tab === 'history' ? 'history' : 'active',
  participation:
    search.participation === 'requested' ||
    search.participation === 'delivering'
      ? search.participation
      : 'all',
})
