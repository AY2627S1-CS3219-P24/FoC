import { Outlet } from '@tanstack/react-router'
import type { ReactNode } from 'react'
import { UserHeader } from '#/components/UserHeader/UserHeader'
import type { UserHeaderProps } from '#/components/UserHeader/UserHeader'
import styles from './UserLayout.module.scss'

type UserLayoutProps = UserHeaderProps & { children?: ReactNode }

export const UserLayout = ({ children, ...headerProps }: UserLayoutProps) => (
  <div className={styles.canvas}>
    <a className={styles.skipLink} href="#user-content">
      Skip to content
    </a>
    <div className={styles.shell}>
      <UserHeader {...headerProps} />
      <main id="user-content" tabIndex={-1} className={styles.content}>
        {children ?? <Outlet />}
      </main>
    </div>
  </div>
)
