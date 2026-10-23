import { useEffect, useState } from 'react'
import { Link, useNavigate, useSearch } from '@tanstack/react-router'
import { Search } from 'lucide-react'
import { OrderCard } from '../../components/OrderCard/OrderCard'
import { OrderFeedback } from '../../components/OrderFeedback/OrderFeedback'
import { OrderPagination } from '../../components/OrderPagination/OrderPagination'
import { useMyErrands, useOrderClock } from '../../hooks/useOrders'
import { getHomeMode, rememberHomeMode } from '../../utils/homeMode'
import styles from '../../styles/order.module.scss'
import pageStyles from './MyErrandsPage.module.scss'

const SEARCH_DELAY_MS = 300

// will follow the home page role switch
export const MyErrandsPage = () => {
  const search = useSearch({ from: '/authenticated/user/errands' })
  const navigate = useNavigate()
  const side =
    search.participation === 'all'
      ? getHomeMode() === 'courier'
        ? 'delivering'
        : 'requested'
      : search.participation
  const delivering = side === 'delivering'
  const [text, setText] = useState(search.q ?? '')

  // A link that names a side also moves Home to that side.
  useEffect(() => {
    if (search.participation !== 'all')
      rememberHomeMode(
        search.participation === 'delivering' ? 'courier' : 'requestor',
      )
  }, [search.participation])

  // Search once typing pauses, from the first page.
  useEffect(() => {
    const q = text.trim() || undefined
    if (q === search.q) return
    const timer = setTimeout(() => {
      void navigate({
        to: '/errands',
        search: { ...search, q, page: 0 },
        replace: true,
      })
    }, SEARCH_DELAY_MS)
    return () => clearTimeout(timer)
  }, [text, search, navigate])

  const query = useMyErrands({
    as: delivering ? 'courier' : 'requester',
    view: search.tab,
    page: search.page,
    size: 20,
    q: search.q,
  })
  const now = useOrderClock()
  return (
    <div className={styles.page}>
      <h1 className={styles.pageTitle}>My Errands</h1>
      <p className={pageStyles.subtitle}>
        {delivering
          ? 'Errands you deliver for others. Switch to Request on Home to see your own requests.'
          : 'Errands you asked others to collect. Switch to Deliver on Home to see your deliveries.'}
      </p>
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
        <label className={pageStyles.search}>
          <Search size={16} aria-hidden="true" />
          <input
            type="search"
            value={text}
            onChange={(event) => setText(event.target.value)}
            placeholder="Search item, shop or place"
            aria-label="Search errands"
          />
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
            <ul className={`${styles.list} ${pageStyles.cards}`}>
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
              {search.q ? (
                <p>No errands match “{search.q}”.</p>
              ) : delivering ? (
                <p>
                  No {search.tab === 'history' ? 'past' : 'current'} deliveries.{' '}
                  <Link
                    className={styles.link}
                    to="/home"
                    search={{ mode: 'courier' }}
                  >
                    Find an errand
                  </Link>
                </p>
              ) : (
                <p>
                  No {search.tab === 'history' ? 'past' : 'current'} requests.{' '}
                  <Link
                    className={styles.link}
                    to="/home"
                    search={{ mode: 'requestor' }}
                  >
                    Request a pickup
                  </Link>
                </p>
              )}
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
