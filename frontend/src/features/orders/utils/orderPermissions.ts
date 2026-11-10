import type { Errand, OrderAction } from '../types/errand.types'

export const isTerminal = (order: Errand) =>
  ['CREATE_FAILED', 'COMPLETED', 'CANCELLED', 'EXPIRED'].includes(order.status)
export const isExpired = (order: Errand, now = Date.now()) =>
  order.status === 'OPEN' && Date.parse(order.expiresAt) <= now
// UI affordances only. The real service must independently enforce these rules.
export const availableOrderActions = (
  order: Errand,
  userId: string,
  now = Date.now(),
): Array<OrderAction> => {
  if (
    isTerminal(order) ||
    (order.dispute && order.dispute.status !== 'RESOLVED')
  )
    return []
  if (order.status === 'OPEN')
    return order.requester.id === userId
      ? ['cancel']
      : isExpired(order, now)
        ? []
        : ['accept']
  if (order.status === 'ACCEPTED' && order.courier?.id === userId)
    return ['pickup']
  // the requester can only confirm after the courier has delivered it with photo
  if (order.status === 'DELIVERED' && order.requester.id === userId)
    return ['complete']
  return []
}
export const canDeliver = (order: Errand, userId: string) =>
  order.status === 'PICKED_UP' && order.courier?.id === userId
