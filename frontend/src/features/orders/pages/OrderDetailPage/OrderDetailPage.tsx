import { Link, useParams } from '@tanstack/react-router'
import { OrderDetails } from '../../components/OrderDetails/OrderDetails'
import { OrderTimeline } from '../../components/OrderTimeline/OrderTimeline'
import { OrderStatusBadge } from '../../components/OrderStatusBadge/OrderStatusBadge'
import { OrderActions } from '../../components/OrderActions/OrderActions'
import {
  OrderPreviewNotice,
  OrderFeedback,
} from '../../components/OrderFeedback/OrderFeedback'
import { useOrder, useOrderClock } from '../../hooks/useOrders'
import { isExpired } from '../../utils/orderPermissions'
import styles from '../../styles/order.module.scss'

export const OrderDetailPage = () => {
  const { orderId } = useParams({ strict: false })
  const query = useOrder(orderId ?? '')
  const now = useOrderClock()
  const order = query.data
  const courier = order?.courier?.id === query.user?.id
  const expired = order && isExpired(order, now)
  const title =
    courier && order?.status === 'ACCEPTED'
      ? `Collect from ${order.pickup.name}`
      : courier && order?.status === 'PICKED_UP'
        ? `Deliver to ${order.requester.name}`
        : 'Errand details'
  return (
    <div className={styles.page}>
      <Link
        to="/errands"
        search={{ tab: 'active', participation: 'all' }}
        className={styles.back}
      >
        ← Back to My Errands
      </Link>
      <OrderPreviewNotice />
      <OrderFeedback
        error={query.error ?? query.identityError}
        loading={query.isPending}
        retry={() => {
          void query.refetch()
        }}
      />
      {order && query.user && !query.error && (
        <>
          <div className={styles.title}>
            <h1>{title}</h1>
            <OrderStatusBadge status={expired ? 'EXPIRED' : order.status} />
          </div>
          <p className={styles.muted}>Errand #{order.id}</p>
          {expired && <p role="status">The acceptance period has ended.</p>}
          <div className={styles.detailGrid}>
            <OrderDetails order={order} />
            <OrderTimeline
              order={
                expired
                  ? { ...order, status: 'EXPIRED', endedAt: order.expiresAt }
                  : order
              }
            />
          </div>
          <OrderActions
            key={order.id}
            order={order}
            user={query.user}
            now={now}
          />
          <Link to="/home" search={{ mode: 'courier' }} className={styles.back}>
            Browse other errands →
          </Link>
        </>
      )}
    </div>
  )
}
