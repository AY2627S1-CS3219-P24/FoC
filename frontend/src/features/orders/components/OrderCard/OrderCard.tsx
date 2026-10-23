import { Link } from '@tanstack/react-router'
import { Clock, MapPin, Store } from 'lucide-react'
import { OrderStatusBadge } from '../OrderStatusBadge/OrderStatusBadge'
import { formatOrderTime, remainingTime } from '../../utils/orderFormat'
import type { Errand } from '../../types/errand.types'
import type { OrderListSearch } from '../../utils/orderFilters'
import styles from './OrderCard.module.scss'

const participants = (order: Errand, userId: string) => {
  if (order.requester.id === userId) {
    return order.courier
      ? `Requested by you · Courier: ${order.courier.name ?? 'Courier'}`
      : 'Requested by you'
  }
  if (order.courier?.id === userId) {
    const delivering =
      order.status === 'COMPLETED' ? 'You delivered' : "You're delivering"
    return `${delivering} · For ${order.requester.name ?? 'Requester'}`
  }
  return `Requested by ${order.requester.name ?? 'Requester'}`
}

export const OrderCard = ({
  order,
  userId,
  now,
  listSearch,
}: {
  order: Errand
  userId: string
  now: number
  listSearch?: OrderListSearch
}) => {
  const courier = order.courier?.id === userId
  return (
    <Link
      className={styles.card}
      to="/errands/$orderId"
      params={{ orderId: order.id }}
      search={{
        tab: listSearch?.tab ?? 'active',
        participation: listSearch?.participation ?? 'all',
        page: listSearch?.page ?? 0,
        posted: false,
      }}
    >
      <div className={styles.body}>
        {order.status === 'OPEN' && (
          <span className={styles.expiry}>
            <Clock size={13} aria-hidden="true" />
            {remainingTime(order.expiresAt, now)}
          </span>
        )}
        <strong className={styles.item}>{order.description}</strong>
        <p className={styles.route}>
          <span>
            <Store size={14} aria-hidden="true" />
            {order.pickup.name}
          </span>
          <span className={styles.to} aria-label="to">
            →
          </span>
          <span>
            <MapPin size={14} aria-hidden="true" />
            {order.deliveryLocation}
          </span>
        </p>
        <span className={styles.people}>
          {participants(order, userId)} ·{' '}
          {formatOrderTime(order.endedAt ?? order.createdAt)}
        </span>
      </div>
      <OrderStatusBadge status={order.status} />
      <span className={styles.credits} data-earned={courier || undefined}>
        {courier ? '+' : ''}
        {order.creditReward} credits
      </span>
      <span className={styles.arrow} aria-hidden="true">
        ›
      </span>
    </Link>
  )
}
