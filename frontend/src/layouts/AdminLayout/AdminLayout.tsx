import { useState } from 'react'
import { Link, Outlet } from '@tanstack/react-router'
import { Button } from '@base-ui/react/button'
import styles from './AdminLayout.module.scss'

// The sidebar sections, add in other pages later on
const sections = [
  { label: 'Overview', to: null },
  { label: 'Suppliers', to: '/admin/suppliers' },
  { label: 'Users', to: null },
  { label: 'Orders', to: null },
  { label: 'Audit log', to: null },
] as const

export const AdminLayout = () => {
  const [menuOpen, setMenuOpen] = useState(false)
  const closeMenu = () => setMenuOpen(false)

  return (
    <div className={styles.layout}>
      <aside className={styles.sidebar} data-open={menuOpen || undefined}>
        <div className={styles.brandRow}>
          <span className={styles.brand}>FoC</span>
          <Button
            className={styles.menuButton}
            aria-label="Toggle navigation"
            aria-expanded={menuOpen}
            onClick={() => setMenuOpen((open) => !open)}
          >
            ☰
          </Button>
        </div>
        <nav className={styles.nav}>
          {sections.map(({ label, to }) =>
            to ? (
              <Link
                key={label}
                to={to}
                className={styles.link}
                activeProps={{ 'data-active': true }}
                onClick={closeMenu}
              >
                {label}
              </Link>
            ) : (
              <span key={label} className={styles.link} aria-disabled="true">
                {label}
              </span>
            ),
          )}
          <Link to="/" className={`${styles.link} ${styles.back}`}>
            ← Back to home
          </Link>
        </nav>
      </aside>
      <main className={styles.main}>
        <Outlet />
      </main>
    </div>
  )
}
