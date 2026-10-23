import { useState } from 'react'
import { Button } from '@base-ui/react/button'
import { Dialog } from '@base-ui/react/dialog'
import { categories } from './requesterHome.data'
import styles from './RequesterHomePage.module.scss'
import { Link } from '@tanstack/react-router'
import { RoleSwitcher } from '#/features/orders/components/RoleSwitcher/RoleSwitcher'
import { OrderCard } from '../../components/OrderCard/OrderCard'
import { OrderFeedback } from '../../components/OrderFeedback/OrderFeedback'
import { useMyErrands, useOrderClock } from '../../hooks/useOrders'

import common from '../../styles/order.module.scss'

export const RequesterHomePage = () => {
  const orders = useMyErrands({ as: 'all', view: 'all', page: 0, size: 3 })
  const now = useOrderClock()
  const recent = orders.data?.items ?? []
  const [preview, setPreview] = useState<{
    title: string
    description: string
  } | null>(null)
  return (
    <div className={`${common.page} ${styles.page}`}>
      <RoleSwitcher activeMode="requestor" />
      <h1>What do you need?</h1>
      <ul className={styles.categories} aria-label="Location categories">
        {categories.map((category) => (
          <li key={category}>
            <Button
              className={styles.category}
              onClick={() =>
                setPreview({
                  title: category,
                  description: `Browse ${category.toLowerCase()} here once the location listing is connected.`,
                })
              }
            >
              <span className={styles.tile} aria-hidden="true" />
              <span>{category}</span>
            </Button>
          </li>
        ))}
      </ul>

      <section className={styles.section} aria-labelledby="favourites-heading">
        <h2 id="favourites-heading">Favourite locations</h2>
        <ul className={styles.cards}>
          <li>
            <div className={styles.card} aria-disabled="true">
              <span className={styles.cardTitle}>
                Favourites not connected yet
              </span>
            </div>
          </li>
        </ul>
      </section>

      <section className={styles.section} aria-labelledby="recent-heading">
        <h2 id="recent-heading">Recent errands</h2>

        <OrderFeedback
          error={orders.error}
          loading={orders.isPending}
          retry={() => void orders.refetch()}
        />
        {!orders.isPending && !orders.error && (
          <ul className={common.list}>
            {recent.map((order) => (
              <li key={order.id}>
                <OrderCard order={order} userId={orders.userId!} now={now} />
              </li>
            ))}
          </ul>
        )}
        {!orders.isPending && !orders.error && recent.length === 0 && (
          <p className={common.muted}>
            No errands yet. Create your first request or switch to Courier to
            help someone.
          </p>
        )}
      </section>
      <Link to="/errands/new" className={`${common.button} ${styles.create}`}>
        Create Errand
      </Link>

      <Dialog.Root
        open={preview !== null}
        onOpenChange={(open) => {
          if (!open) setPreview(null)
        }}
      >
        <Dialog.Portal>
          <Dialog.Backdrop className={styles.backdrop} />
          <Dialog.Popup className={styles.dialog}>
            <Dialog.Title>{preview?.title}</Dialog.Title>
            <Dialog.Description>{preview?.description}</Dialog.Description>
            <Dialog.Close className={styles.create}>Close</Dialog.Close>
          </Dialog.Popup>
        </Dialog.Portal>
      </Dialog.Root>
    </div>
  )
}
