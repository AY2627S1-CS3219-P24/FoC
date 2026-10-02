import { useState } from 'react'
import { Link } from '@tanstack/react-router'
import { Dialog } from '@base-ui/react/dialog'
import { SUPPLIER_CATEGORIES } from '#/features/suppliers/types/supplier.types'
import { categoryLabels } from '#/features/suppliers/utils/supplierFormat'
import { categoryImages } from './requesterHome.data'
import styles from './RequesterHomePage.module.scss'
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
        {SUPPLIER_CATEGORIES.map((category) => (
          <li key={category}>
            <Link
              to="/locations"
              search={{ category }}
              className={styles.category}
            >
              <span className={styles.tile} aria-hidden="true">
                <img src={categoryImages[category]} alt="" />
              </span>
              <span>{categoryLabels[category]}</span>
            </Link>
          </li>
        ))}
      </ul>

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
