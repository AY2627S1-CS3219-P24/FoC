import type { Errand } from '../../types/errand.types'
import { formatOrderTime } from '../../utils/orderFormat'
import styles from './OrderDetails.module.scss'

const creditLabels = {
  RESERVED: 'Reserved',
  SETTLEMENT_PENDING: 'Settlement pending',
  SETTLED: 'Settled',
  RELEASE_PENDING: 'Release pending',
  RELEASED: 'Released',
}
export const OrderDetails = ({ order }: { order: Errand }) => (
  <section className={styles.panel}>
    <h2>
      {order.pickup.name} → {order.delivery}
    </h2>
    <dl className={styles.details}>
      <dt>Pickup location</dt>
      <dd>
        {order.pickup.name}
        <small>{order.pickup.address}</small>
      </dd>
      <dt>Deliver to</dt>
      <dd>{order.delivery}</dd>
      <dt>Item(s)</dt>
      <dd>{order.description}</dd>
      {order.notes && (
        <>
          <dt>Notes</dt>
          <dd>{order.notes}</dd>
        </>
      )}
      <dt>Requester</dt>
      <dd>{order.requester.name}</dd>
      <dt>Courier</dt>
      <dd>{order.courier?.name ?? 'Not assigned yet'}</dd>
      <dt>Reward</dt>
      <dd>
        {order.reward} credits <small>{creditLabels[order.creditState]}</small>
      </dd>
      {order.status === 'OPEN' && (
        <>
          <dt>Accept before</dt>
          <dd>{formatOrderTime(order.expiresAt)}</dd>
        </>
      )}
    </dl>
  </section>
)
