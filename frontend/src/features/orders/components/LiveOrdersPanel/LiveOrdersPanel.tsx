import { Panel } from '#/components/Panel/Panel'
import type { OrderStatus, OrderSummary } from '../../types/order.types'
import styles from './LiveOrdersPanel.module.scss'

const statusLabels: Record<OrderStatus, string> = {
  REQUESTED: 'Requested',
  ACCEPTED: 'Accepted',
  PICKED_UP: 'Picked up',
  DELIVERED: 'Delivered',
  CANCELLED: 'Cancelled',
}

type LiveOrdersPanelProps = {
  orders: Array<OrderSummary>
}

export const LiveOrdersPanel = ({ orders }: LiveOrdersPanelProps) => (
  <Panel
    title="Live Orders"
    action={
      <button
        type="button"
        className={styles.viewAll}
        disabled
        title="Order management is not available yet"
      >
        View all
      </button>
    }
  >
    <div className={styles.tableWrapper}>
      <table className={styles.table}>
        <thead>
          <tr>
            <th>Order</th>
            <th>Requester</th>
            <th>Courier</th>
            <th>Pickup</th>
            <th>Status</th>
            <th>Credits</th>
          </tr>
        </thead>
        <tbody>
          {orders.map((order) => (
            <tr key={order.id}>
              <td className={styles.id}>#{order.id}</td>
              <td>
                {order.requester ?? <span className={styles.none}>—</span>}
              </td>
              <td>{order.courier ?? <span className={styles.none}>—</span>}</td>
              <td>{order.pickup}</td>
              <td>
                <span className={styles.status} data-status={order.status}>
                  {statusLabels[order.status]}
                </span>
              </td>
              <td>{order.credits}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  </Panel>
)
