import { useEffect, useState } from 'react'
import { useNavigate, useSearch } from '@tanstack/react-router'
import { RoleSwitcher } from '../../components/RoleSwitcher/RoleSwitcher'
import { OrderCard } from '../../components/OrderCard/OrderCard'
import { OrderFeedback } from '../../components/OrderFeedback/OrderFeedback'
import { OrderPagination } from '../../components/OrderPagination/OrderPagination'
import { useAvailableOrders, useOrderClock } from '../../hooks/useOrders'
import styles from '../../styles/order.module.scss'

export const CourierHomePage = () => {
  const search = useSearch({ from: '/authenticated/user/home' })
  const navigate = useNavigate()
  const [input, setInput] = useState(search.q ?? '')
  useEffect(() => setInput(search.q ?? ''), [search.q])
  useEffect(() => {
    if (input === (search.q ?? '')) return
    const timer = setTimeout(
      () =>
        void navigate({
          to: '/home',
          search: { mode: 'courier', q: input, page: 0 },
          replace: true,
        }),
      300,
    )
    return () => clearTimeout(timer)
  }, [input, search.q, navigate])
  const orders = useAvailableOrders(search.q ?? '', search.page ?? 0)
  const now = useOrderClock()
  return (
    <section className={styles.page} aria-labelledby="courier-heading">
      <RoleSwitcher activeMode="courier" />
      <div className={styles.toolbar}>
        <div>
          <h1 id="courier-heading" className={styles.pageTitle}>
            Find an errand
          </h1>
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
            value={input}
            onChange={(event) => setInput(event.target.value)}
          />
        </label>
      </div>
      <OrderFeedback
        error={orders.error}
        loading={orders.isPending}
        retry={() => void orders.refetch()}
      />
      {orders.data && orders.userId && !orders.error && (
        <>
          <ul className={styles.list}>
            {orders.data.items.map((order) => (
              <li key={order.id}>
                <OrderCard order={order} userId={orders.userId!} now={now} />
              </li>
            ))}
          </ul>
          {!orders.data.total && (
            <p className={styles.panel} role="status">
              {search.q
                ? 'No errands match your search. Try another location or description.'
                : 'No open errands right now. Check back soon.'}
            </p>
          )}
          <OrderPagination
            page={search.page ?? 0}
            total={orders.data.total}
            pending={orders.isFetching}
            onPage={(page) =>
              void navigate({ to: '/home', search: { ...search, page } })
            }
          />
        </>
      )}
    </section>
  )
}
