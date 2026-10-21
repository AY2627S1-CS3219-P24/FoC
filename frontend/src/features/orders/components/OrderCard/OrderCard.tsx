import { Link } from '@tanstack/react-router'
import { OrderStatusBadge } from '../OrderStatusBadge/OrderStatusBadge'
import { remainingTime } from '../../utils/orderFormat'
import type { Errand } from '../../types/errand.types'
import type { OrderListSearch } from '../../utils/orderFilters'
import styles from './OrderCard.module.scss'

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
      <div className={styles.description}>
        <strong>
          {order.pickup.name} → {order.deliveryLocation}
        </strong>
        <span>
          {order.requester.id === userId
            ? 'Requested by you'
            : order.courier?.id === userId
              ? "You're delivering"
              : order.description}
        </span>
      </div>
      <OrderStatusBadge status={order.status} />
      <div className={styles.meta}>
        <span>{order.creditReward} credits</span>
        {order.status === 'OPEN' && (
          <small>{remainingTime(order.expiresAt, now)}</small>
        )}
      </div>
      <span className={styles.arrow} aria-hidden="true">
        ›
      </span>
    </Link>
  )
}
