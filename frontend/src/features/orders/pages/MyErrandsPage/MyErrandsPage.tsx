import { Link, useNavigate, useSearch } from '@tanstack/react-router'
import { OrderCard } from '../../components/OrderCard/OrderCard'
import {
  OrderPreviewNotice,
  OrderFeedback,
} from '../../components/OrderFeedback/OrderFeedback'
import { useOrderClock, useOrders } from '../../hooks/useOrders'
import { isExpired, isTerminal } from '../../utils/orderPermissions'
import type { OrderListSearch } from '../../types/errand.types'
import styles from '../../styles/order.module.scss'

export const MyErrandsPage = () => {
  const search = useSearch({ from: '/authenticated/user/errands' })
  const navigate = useNavigate()
  const query = useOrders()
  const now = useOrderClock()
  const orders = (query.data ?? []).filter((order) => {
    const requested = order.requester.id === query.user?.id
    const delivering = order.courier?.id === query.user?.id
    return (
      (requested || delivering) &&
      (search.participation === 'all' ||
        (search.participation === 'requested' ? requested : delivering)) &&
      (isTerminal(order) || isExpired(order, now)) ===
        (search.tab === 'history')
    )
  })
  return (
    <div className={styles.page}>
      <h1>My Errands</h1>
      <OrderPreviewNotice />
      <div className={styles.toolbar}>
        <nav className={styles.tabs} aria-label="Errand history">
          <Link
            to="/errands"
            search={{ ...search, tab: 'active' }}
            aria-current={search.tab === 'active' ? 'page' : undefined}
          >
            Active
          </Link>
          <Link
            to="/errands"
            search={{ ...search, tab: 'history' }}
            aria-current={search.tab === 'history' ? 'page' : undefined}
          >
            History
          </Link>
        </nav>
        <label className={styles.filter}>
          Type
          <select
            className={styles.select}
            value={search.participation}
            onChange={(event) => {
              void navigate({
                to: '/errands',
                search: {
                  ...search,
                  participation: event.target
                    .value as OrderListSearch['participation'],
                },
              })
            }}
          >
            <option value="all">All</option>
            <option value="requested">Requested by me</option>
            <option value="delivering">I'm delivering</option>
          </select>
        </label>
      </div>
      <OrderFeedback
        error={query.error ?? query.identityError}
        loading={query.isPending}
        retry={() => {
          void query.refetch()
        }}
      />
      {query.data && (
        <>
          {orders.length ? (
            <ul className={styles.list}>
              {orders.map((order) => (
                <li key={order.id}>
                  <OrderCard order={order} userId={query.user!.id} now={now} />
                </li>
              ))}
            </ul>
          ) : (
            <div className={styles.panel}>
              <p>
                No {search.tab === 'history' ? 'past' : 'active'} errands match
                this view.
              </p>
              <Link to="/home" search={{ mode: 'requestor' }}>
                Request a pickup
              </Link>{' '}
              or{' '}
              <Link to="/home" search={{ mode: 'courier' }}>
                find an errand
              </Link>
              .
            </div>
          )}
        </>
      )}
    </div>
  )
}
