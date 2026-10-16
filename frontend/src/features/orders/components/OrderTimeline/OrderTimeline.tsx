import { formatOrderTime } from '../../utils/orderFormat'
import type { Errand } from '../../types/errand.types'
import styles from './OrderTimeline.module.scss'

export const OrderTimeline = ({ order }: { order: Errand }) => {
  const endedEarly = order.status === 'CANCELLED' || order.status === 'EXPIRED'
  const steps = endedEarly
    ? [
        { label: 'Request created', at: order.createdAt },
        {
          label: order.status === 'CANCELLED' ? 'Cancelled' : 'Expired',
          at: order.endedAt ?? order.expiresAt,
        },
      ]
    : [
        { label: 'Request created', at: order.createdAt },
        { label: 'Courier accepted', at: order.acceptedAt },
        { label: 'Picked up', at: order.pickedUpAt },
        {
          label: 'Received',
          at: order.status === 'COMPLETED' ? order.endedAt : undefined,
        },
      ]
  return (
    <section className={styles.panel} aria-label="Order progress">
      <h2>Delivery progress</h2>
      <ol className={styles.timeline}>
        {steps.map((step, index) => (
          <li
            key={step.label}
            data-done={!!step.at}
            data-terminal={endedEarly && index === steps.length - 1}
          >
            <span className={styles.node} aria-hidden="true">
              {step.at ? (endedEarly && index > 0 ? '–' : '✓') : ''}
            </span>
            <div>
              {step.label}
              <small>{step.at ? formatOrderTime(step.at) : 'Pending'}</small>
            </div>
          </li>
        ))}
      </ol>
    </section>
  )
}
