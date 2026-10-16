import type { Errand, OrderAction } from '../types/errand.types'

export const isTerminal = (order: Errand) =>
  ['COMPLETED', 'CANCELLED', 'EXPIRED'].includes(order.status)
export const isExpired = (order: Errand, now = Date.now()) =>
  order.status === 'OPEN' && Date.parse(order.expiresAt) <= now
// UI affordances only. The real service must independently enforce these rules.
export const availableOrderActions = (
  order: Errand,
  userId: string,
  now = Date.now(),
): Array<OrderAction> => {
  if (isTerminal(order) || isExpired(order, now)) return []
  if (order.status === 'OPEN')
    return [order.requester.id === userId ? 'cancel' : 'accept']
  if (order.status === 'ACCEPTED' && order.courier?.id === userId)
    return ['pickup']
  if (order.status === 'PICKED_UP' && order.requester.id === userId)
    return ['complete']
  return []
}
