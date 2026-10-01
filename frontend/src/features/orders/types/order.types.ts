export type OrderStatus =
  'REQUESTED' | 'ACCEPTED' | 'PICKED_UP' | 'DELIVERED' | 'CANCELLED'

/** Summary row show the stats for admin */
export type OrderSummary = {
  id: number
  requester: string | null
  courier: string | null
  pickup: string
  status: OrderStatus
  credits: number
}
