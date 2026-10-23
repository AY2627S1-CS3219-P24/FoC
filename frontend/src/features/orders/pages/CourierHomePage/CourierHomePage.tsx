import { useEffect, useState } from 'react'
import { Link, useNavigate, useSearch } from '@tanstack/react-router'
import { OrderActions } from '../../components/OrderActions/OrderActions'
import { OrderFeedback } from '../../components/OrderFeedback/OrderFeedback'
import { OrderPagination } from '../../components/OrderPagination/OrderPagination'
import { useAvailableOrders, useOrderClock } from '../../hooks/useOrders'
import { orderErrorMessage } from '../../utils/orderErrors'
import type { Errand } from '../../types/errand.types'
import styles from '../../styles/order.module.scss'
import page from './CourierHomePage.module.scss'

export const CourierHomePage = () => {
  const search = useSearch({ from: '/authenticated/user/deliver' })
  const navigate = useNavigate()
  const [input, setInput] = useState(search.q ?? '')
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [actionError, setActionError] = useState<Error | null>(null)
  useEffect(() => setInput(search.q ?? ''), [search.q])
  useEffect(() => {
    if (input === (search.q ?? '')) return
    const timer = setTimeout(
      () =>
        void navigate({
          to: '/deliver',
          search: { q: input, page: 0 },
          replace: true,
        }),
      300,
    )
    return () => clearTimeout(timer)
  }, [input, search.q, navigate])
  const orders = useAvailableOrders(search.q ?? '', search.page ?? 0)
  const now = useOrderClock()
  const items = orders.data?.items ?? []
  const selected =
    items.find((order) => order.id === selectedId) ?? items[0] ?? null

  return (
    <section className={styles.page} aria-labelledby="courier-heading">
      <div className={styles.toolbar}>
        <div>
          <h1 id="courier-heading" className={styles.pageTitle}>
            Find an errand
          </h1>
          <p className={styles.muted}>Choose a pickup that fits your trip.</p>
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
          <div className={page.layout}>
            <div>
              <ul className={page.list}>
                {items.map((order) => (
                  <li key={order.id}>
                    <button
                      type="button"
                      className={
                        selected?.id === order.id ? page.selected : page.row
                      }
                      aria-current={
                        selected?.id === order.id ? 'true' : undefined
                      }
                      onClick={() => {
                        setSelectedId(order.id)
                        setActionError(null)
                      }}
                    >
                      <ErrandSummary order={order} />
                    </button>
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
            </div>
            {selected && (
              <aside className={page.detail} aria-labelledby="trip-heading">
                <h2 id="trip-heading">Could this fit your trip?</h2>
                <p className={page.route}>
                  <strong>
                    {selected.pickup.name} · {selected.pickup.address}
                  </strong>
                  <span>{selected.deliveryLocation}</span>
                </p>
                <p>{selected.description}</p>
                {selected.notes ? (
                  <p className={styles.muted}>{selected.notes}</p>
                ) : null}
                <p className={page.credits}>{selected.creditReward} credits</p>
                <Link
                  to="/errands/$orderId"
                  params={{ orderId: selected.id }}
                  search={{
                    tab: 'active',
                    participation: 'all',
                    page: search.page ?? 0,
                    posted: false,
                  }}
                >
                  View route
                </Link>
                {actionError && (
                  <p role="alert">{orderErrorMessage(actionError)}</p>
                )}
                <OrderActions
                  key={`${orders.userId}:${selected.id}`}
                  order={selected}
                  userId={orders.userId}
                  now={now}
                  onError={setActionError}
                />
              </aside>
            )}
          </div>
          <OrderPagination
            page={search.page ?? 0}
            total={orders.data.total}
            pending={orders.isFetching}
            onPage={(pageNumber) =>
              void navigate({
                to: '/deliver',
                search: { ...search, page: pageNumber },
              })
            }
          />
        </>
      )}
    </section>
  )
}

const ErrandSummary = ({ order }: { order: Errand }) => (
  <>
    <strong>
      {order.pickup.name} · {order.pickup.address}
    </strong>
    <span>{order.deliveryLocation}</span>
    <span>{order.description}</span>
    <span>
      {order.creditReward} credits
      {order.status === 'OPEN' ? ' · Pickup ready' : ''}
    </span>
  </>
)
