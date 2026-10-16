import { Button } from '@base-ui/react/button'
import { ordersPreviewEnabled } from '../../api/orders.api'
import styles from '../../styles/order.module.scss'

export const OrderFeedback = ({
  error,
  loading,
  retry,
}: {
  error?: Error | null
  loading?: boolean
  retry?: () => void
}) =>
  error ? (
    <div className={styles.feedback} role="alert">
      <p>{error.message}</p>
      {retry && (
        <Button className={styles.secondary} onClick={retry}>
          Try again
        </Button>
      )}
    </div>
  ) : loading ? (
    <p role="status">Loading errands…</p>
  ) : null
export const OrderPreviewNotice = () =>
  ordersPreviewEnabled ? (
    <p className={styles.notice}>
      UI preview · Sample data only. Changes reset on reload; no real orders or
      credits are affected.
    </p>
  ) : null
