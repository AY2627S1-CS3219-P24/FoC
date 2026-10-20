import { useState } from 'react'
import { Button } from '@base-ui/react/button'
import { Dialog } from '@base-ui/react/dialog'
import { categories, favouriteLocations } from './requesterHome.data'
import styles from './RequesterHomePage.module.scss'
import { Link } from '@tanstack/react-router'
import { RoleSwitcher } from '#/features/orders/components/RoleSwitcher/RoleSwitcher'
import { OrderCard } from '../../components/OrderCard/OrderCard'
import {
  OrderPreviewNotice,
  OrderFeedback,
} from '../../components/OrderFeedback/OrderFeedback'
import { useOrders, useOrderClock } from '../../hooks/useOrders'
import { ordersPreviewEnabled } from '../../api/orders.api'
import common from '../../styles/order.module.scss'

export const RequesterHomePage = () => {
  const orders = useOrders()
  const now = useOrderClock()
  const recent = (orders.data ?? [])
    .filter(
      (order) =>
        order.requester.id === orders.user?.id ||
        order.courier?.id === orders.user?.id,
    )
    .sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt))
    .slice(0, 3)
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
        {!ordersPreviewEnabled && (
          <p className={common.muted}>
            Your favourite locations will appear here when favourites are
            connected.
          </p>
        )}
        {ordersPreviewEnabled && (
          <ul className={styles.cards}>
            {favouriteLocations.map((location) => (
              <li key={location.id}>
                <Button
                  className={styles.card}
                  onClick={() =>
                    setPreview({
                      title: location.name,
                      description: `${location.hours}. This is a sample location; live hours and location details are not connected yet.`,
                    })
                  }
                >
                  <span>
                    <span className={styles.cardTitle}>{location.name}</span>{' '}
                    <span className={styles.status}>{location.hours}</span>
                  </span>
                  <span className={styles.arrow} aria-hidden="true">
                    ›
                  </span>
                </Button>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className={styles.section} aria-labelledby="recent-heading">
        <h2 id="recent-heading">Recent errands</h2>
        <OrderPreviewNotice />
        <OrderFeedback
          error={orders.error ?? orders.identityError}
          loading={orders.isPending}
          retry={() => void orders.refetch()}
        />
        {!orders.isPending && !orders.error && (
          <ul className={common.list}>
            {recent.map((order) => (
              <li key={order.id}>
                <OrderCard order={order} userId={orders.user!.id} now={now} />
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
