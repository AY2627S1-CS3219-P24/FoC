import { useMemo } from 'react'
import { Link } from '@tanstack/react-router'
import { StatCard } from '#/components/StatCard/StatCard'
import { LiveOrdersPanel } from '#/features/orders/components/LiveOrdersPanel/LiveOrdersPanel'
import {
  sampleLiveOrders,
  sampleOrderStats,
} from '#/features/orders/mocks/adminOverview.mock'
import { SupplierSummaryPanel } from '#/features/suppliers/components/SupplierSummaryPanel/SupplierSummaryPanel'
import { useSuppliers } from '#/features/suppliers/hooks/useSuppliers'
import { summarizeSuppliers } from '#/features/suppliers/utils/supplierFormat'
import styles from './AdminOverviewPage.module.scss'

// temp data here, TODO afterwards
const sampleStudentStats = { registered: 7201, joinedThisWeek: 52 }

export const AdminOverviewPage = () => {
  // include deactivated suppliers to summary
  const suppliers = useSuppliers({ inactive: true })
  const summary = useMemo(
    () =>
      suppliers.data ? summarizeSuppliers(suppliers.data, new Date()) : null,
    [suppliers.data],
  )

  return (
    <section className={styles.page}>
      <h1 className={styles.title}>Overview</h1>

      <div className={styles.stats}>
        <StatCard
          label="Active suppliers"
          value={summary?.active ?? '—'}
          tone={summary ? 'default' : 'pending'}
          hint={
            summary && (
              <div className={styles.links}>
                <Link
                  to="/admin/suppliers"
                  search={{ openNow: true }}
                  className={styles.positiveLink}
                >
                  {summary.openNow} open now →
                </Link>
                {summary.deactivated > 0 && (
                  <Link
                    to="/admin/suppliers"
                    search={{ inactive: true }}
                    className={styles.mutedLink}
                  >
                    {summary.deactivated} deactivated
                  </Link>
                )}
              </div>
            )
          }
        />
        <StatCard
          label="Open errands"
          value={sampleOrderStats.openErrands}
          hint={`+ ${sampleOrderStats.openErrandsSinceYesterday} since yesterday`}
          tone="positive"
        />
        <StatCard
          label="Registered students"
          value={sampleStudentStats.registered.toLocaleString('en-SG')}
          hint={`+ ${sampleStudentStats.joinedThisWeek} this week`}
          tone="positive"
        />
        <StatCard
          label="Open disputes"
          value={sampleOrderStats.openDisputes}
          tone="danger"
        />
      </div>

      <LiveOrdersPanel orders={sampleLiveOrders} />

      <SupplierSummaryPanel
        suppliers={suppliers.data}
        isPending={suppliers.isPending}
        error={suppliers.error}
      />
    </section>
  )
}
