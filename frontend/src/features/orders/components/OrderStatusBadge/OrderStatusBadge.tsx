import type { ErrandStatus } from '../../types/errand.types'
import styles from './OrderStatusBadge.module.scss'

export const OrderStatusBadge = ({ status }: { status: ErrandStatus }) => (
  <span className={styles.badge} data-status={status}>
    {status === 'CREATING'
      ? 'Creating'
      : status === 'CREATE_FAILED'
        ? 'Creation failed'
        : status}
  </span>
)
