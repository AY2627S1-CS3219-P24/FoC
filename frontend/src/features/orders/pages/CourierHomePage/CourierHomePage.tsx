import { useState } from 'react'
import { RoleSwitcher } from '#/features/orders/components/RoleSwitcher/RoleSwitcher'
import { OrderCard } from '../../components/OrderCard/OrderCard'
import {
  OrderPreviewNotice,
  OrderFeedback,
} from '../../components/OrderFeedback/OrderFeedback'
import { useOrders, useOrderClock } from '../../hooks/useOrders'
import { isExpired } from '../../utils/orderPermissions'
import styles from '../../styles/order.module.scss'

export const CourierHomePage = () => {
  const [search, setSearch] = useState('')
  const orders = useOrders()
  const now = useOrderClock()
  const query = search.trim().toLowerCase()
  const results = (orders.data ?? []).filter(
    (order) =>
      order.status === 'OPEN' &&
      !isExpired(order, now) &&
      `${order.pickup.name} ${order.delivery} ${order.description}`
        .toLowerCase()
        .includes(query),
  )
  return (
    <section className={styles.page} aria-labelledby="courier-heading">
      <RoleSwitcher activeMode="courier" />
      <div className={styles.toolbar}>
        <div>
          <h1 id="courier-heading">Find an errand</h1>
          <p className={styles.muted}>
            Help someone on campus and earn credits.
          </p>
        </div>
        <label className={styles.search}>
          Search errands
          <input
            className={styles.input}
            type="search"
            placeholder="Location or description"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
        </label>
      </div>
      <OrderPreviewNotice />
      <OrderFeedback
        error={orders.error ?? orders.identityError}
        loading={orders.isPending}
        retry={() => void orders.refetch()}
      />
      {!orders.isPending && !orders.error && orders.user && (
        <>
          <ul className={styles.list}>
            {results.map((order) => (
              <li key={order.id}>
                <OrderCard
                  order={order}
                  userId={orders.user!.id}
                  now={now}
                  browsing
                />
              </li>
            ))}
          </ul>
          {results.length === 0 && (
            <p className={styles.panel} role="status">
              {query
                ? 'No errands match your search. Try another location or description.'
                : 'No open errands right now. Check back soon.'}
            </p>
          )}
        </>
      )}
    </section>
  )
}
