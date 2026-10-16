import { Link } from '@tanstack/react-router'
import { OrderStatusBadge } from '../OrderStatusBadge/OrderStatusBadge'
import { isExpired } from '../../utils/orderPermissions'
import { remainingTime } from '../../utils/orderFormat'
import type { Errand } from '../../types/errand.types'
import styles from './OrderCard.module.scss'

export const OrderCard = ({
  order,
  userId,
  now,
  browsing = false,
}: {
  order: Errand
  userId: string
  now: number
  browsing?: boolean
}) => (
  <Link
    className={styles.card}
    to="/errands/$orderId"
    params={{ orderId: order.id }}
  >
    <div className={styles.description}>
      <strong>
        {order.pickup.name} → {order.delivery}
      </strong>
      <span>
        {order.requester.id === userId
          ? 'Requested by you'
          : order.courier?.id === userId
            ? "You're delivering"
            : order.description}
      </span>
    </div>
    <OrderStatusBadge
      status={isExpired(order, now) ? 'EXPIRED' : order.status}
    />
    <div className={styles.meta}>
      <span>{order.reward} credits</span>
      {order.status === 'OPEN' && (
        <small>{remainingTime(order.expiresAt, now)}</small>
      )}
      {browsing && order.requester.id === userId && (
        <small>Your request · Cannot accept</small>
      )}
    </div>
    <span className={styles.arrow} aria-hidden="true">
      ›
    </span>
  </Link>
)
