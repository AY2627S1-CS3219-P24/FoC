import { Link, useNavigate, useSearch } from '@tanstack/react-router'
import { OrderCard } from '../../components/OrderCard/OrderCard'
import { OrderFeedback } from '../../components/OrderFeedback/OrderFeedback'
import { OrderPagination } from '../../components/OrderPagination/OrderPagination'
import { useMyErrands, useOrderClock } from '../../hooks/useOrders'
import type { OrderListSearch } from '../../utils/orderFilters'
import styles from '../../styles/order.module.scss'
import pageStyles from './MyErrandsPage.module.scss'

export const MyErrandsPage = () => {
  const search = useSearch({ from: '/authenticated/user/errands' })
  const navigate = useNavigate()
  const query = useMyErrands({
    as:
      search.participation === 'requested'
        ? 'requester'
        : search.participation === 'delivering'
          ? 'courier'
          : 'all',
    view: search.tab,
    page: search.page,
    size: 20,
  })
  const now = useOrderClock()
  return (
    <div className={styles.page}>
      <h1 className={styles.pageTitle}>My Errands</h1>
      <div className={styles.toolbar}>
        <nav className={pageStyles.tabs} aria-label="Errand history">
          {(['active', 'history'] as const).map((tab) => (
            <Link
              className={pageStyles.tab}
              key={tab}
              to="/errands"
              search={{ ...search, tab, page: 0 }}
              aria-current={search.tab === tab ? 'page' : undefined}
            >
              {tab === 'active' ? 'Active' : 'History'}
            </Link>
          ))}
        </nav>
        <label className={pageStyles.filter}>
          Type
          <select
            className={styles.select}
            value={search.participation}
            onChange={(event) =>
              void navigate({
                to: '/errands',
                search: {
                  ...search,
                  participation: event.target
                    .value as OrderListSearch['participation'],
                  page: 0,
                },
              })
            }
          >
            <option value="all">All</option>
            <option value="requested">Requested by me</option>
            <option value="delivering">I'm delivering</option>
          </select>
        </label>
      </div>
      <OrderFeedback
        error={query.error}
        loading={query.isPending}
        retry={() => void query.refetch()}
      />
      {query.data && query.userId && !query.error && (
        <>
          {query.data.items.length ? (
            <ul className={styles.list}>
              {query.data.items.map((order) => (
                <li key={order.id}>
                  <OrderCard
                    order={order}
                    userId={query.userId!}
                    now={now}
                    listSearch={search}
                  />
                </li>
              ))}
            </ul>
          ) : (
            <div className={styles.panel}>
              <p>
                No {search.tab === 'history' ? 'past' : 'active'} errands match
                this view.
              </p>
              <Link
                className={styles.link}
                to="/home"
                search={{ mode: 'requestor' }}
              >
                Request a pickup
              </Link>{' '}
              or{' '}
              <Link
                className={styles.link}
                to="/home"
                search={{ mode: 'courier' }}
              >
                find an errand
              </Link>
              .
            </div>
          )}
          <OrderPagination
            page={search.page}
            total={query.data.total}
            pending={query.isFetching}
            onPage={(page) =>
              void navigate({
                to: '/errands',
                search: { ...search, page },
                replace: true,
              })
            }
          />
        </>
      )}
    </div>
  )
}
