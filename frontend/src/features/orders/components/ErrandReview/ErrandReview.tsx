import { Button } from '@base-ui/react/button'
import type { CreateErrandValues } from '../../schemas/createErrand.schema'
import { expiryLabel } from '../../utils/orderFormat'
import details from '../OrderDetails/OrderDetails.module.scss'
import styles from '../../styles/order.module.scss'

export const ErrandReview = ({
  values,
  onEdit,
  onSubmit,
  pending,
  error,
}: {
  values: CreateErrandValues
  onEdit: () => void
  onSubmit: () => void
  pending: boolean
  error?: Error | null
}) => (
  <section className={styles.panel}>
    <dl className={details.details}>
      <dt>Pickup location</dt>
      <dd>
        {values.pickup?.name}
        <small>{values.pickup?.address}</small>
      </dd>
      <dt>Deliver to</dt>
      <dd>{values.delivery}</dd>
      <dt>Item</dt>
      <dd>{values.description}</dd>
      <dt>Notes</dt>
      <dd>{values.notes || 'None'}</dd>
      <dt>Reward</dt>
      <dd>{values.reward} credits</dd>
      <dt>Expiry</dt>
      <dd>{expiryLabel(values.expiryMinutes)} after posting</dd>
    </dl>
    <p className={styles.muted}>
      {values.reward} credits will be reserved when your request is posted.
    </p>
    {error && (
      <p className={styles.error} role="alert">
        {error.message} Your request details have been kept.
      </p>
    )}
    <div className={styles.actions}>
      <Button className={styles.secondary} disabled={pending} onClick={onEdit}>
        Back to edit
      </Button>
      <Button className={styles.button} disabled={pending} onClick={onSubmit}>
        {pending ? 'Posting…' : 'Post request'}
      </Button>
    </div>
  </section>
)
