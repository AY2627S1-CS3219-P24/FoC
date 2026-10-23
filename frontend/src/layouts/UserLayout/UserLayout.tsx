import { Outlet } from '@tanstack/react-router'
import type { ReactNode } from 'react'
import { UserHeader } from '#/components/UserHeader/UserHeader'
import type { UserHeaderProps } from '#/components/UserHeader/UserHeader'
import styles from './UserLayout.module.scss'

type UserLayoutProps = UserHeaderProps & {
  children?: ReactNode
  overlay?: boolean
}

export const UserLayout = ({
  children,
  overlay = false,
  ...headerProps
}: UserLayoutProps) => (
  <div className={overlay ? styles.mapCanvas : styles.canvas}>
    <a className={styles.skipLink} href="#user-content">
      Skip to content
    </a>
    <div className={overlay ? styles.mapShell : styles.shell}>
      <UserHeader {...headerProps} overlay={overlay} />
      <main
        id="user-content"
        tabIndex={-1}
        className={overlay ? styles.mapContent : styles.content}
      >
        {children ?? <Outlet />}
      </main>
    </div>
  </div>
)
