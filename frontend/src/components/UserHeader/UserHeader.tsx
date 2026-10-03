import { useState } from 'react'
import { Button } from '@base-ui/react/button'
import { Menu } from '@base-ui/react/menu'
import type { ReactNode } from 'react'
import { ChevronDown } from 'lucide-react'
import styles from './UserHeader.module.scss'

export type UserHeaderProps = {
  navigation: ReactNode
  brand?: ReactNode
  name: string
  avatarUrl?: string | null
  availableCredits?: number | null
  hasUnreadNotifications?: boolean
  onNotificationsClick?: () => void
  onAccountClick?: () => void
  onLogout?: () => void
}

export const UserHeader = ({
  navigation,
  brand = 'FoC',
  name,
  avatarUrl,
  availableCredits,
  hasUnreadNotifications = false,
  onNotificationsClick,
  onAccountClick,
  onLogout,
}: UserHeaderProps) => {
  const [failedAvatar, setFailedAvatar] = useState<string | null>(null)
  const initials =
    name
      .trim()
      .split(/\s+/)
      .slice(0, 2)
      .map((part) => part[0])
      .join('')
      .toUpperCase() || '?'
  const account = (
    <>
      <span className={styles.avatar} aria-hidden="true">
        {avatarUrl && avatarUrl !== failedAvatar ? (
          <img
            src={avatarUrl}
            alt=""
            onError={() => setFailedAvatar(avatarUrl)}
          />
        ) : (
          initials
        )}
      </span>
      <span className={styles.identity}>
        <span className={styles.name}>{name}</span>
        <span className={styles.balance}>
          {availableCredits == null
            ? 'Balance unavailable'
            : `${availableCredits} credits`}
        </span>
      </span>
      {onAccountClick && (
        <ChevronDown
          className={styles.chevron}
          size={12}
          strokeWidth={2}
          aria-hidden="true"
        />
      )}
    </>
  )

  return (
    <header className={styles.header}>
      <div className={styles.brand}>{brand}</div>
      <nav aria-label="Main navigation" className={styles.navigation}>
        {navigation}
      </nav>
      <div className={styles.actions}>
        <Button
          className={styles.notifications}
          onClick={onNotificationsClick}
          disabled={!onNotificationsClick}
          aria-label={
            hasUnreadNotifications
              ? 'Notifications, unread notifications'
              : 'Notifications'
          }
          title={
            onNotificationsClick ? 'Notifications' : 'Notifications unavailable'
          }
        >
          <svg
            width="22"
            height="22"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            aria-hidden="true"
          >
            <path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4" />
          </svg>
          {hasUnreadNotifications && <span className={styles.dot} />}
        </Button>
        {onAccountClick ? (
          <Menu.Root>
            <Menu.Trigger
              className={styles.account}
              aria-label={`Open account for ${name}`}
            >
              {account}
            </Menu.Trigger>
            <Menu.Portal>
              <Menu.Positioner
                align="end"
                sideOffset={12}
                className={styles.menuPositioner}
              >
                <Menu.Popup className={styles.menu}>
                  <Menu.Item
                    className={styles.menuItem}
                    onClick={onAccountClick}
                  >
                    Profile
                  </Menu.Item>
                  {onLogout && (
                    <Menu.Item className={styles.logout} onClick={onLogout}>
                      Log out
                    </Menu.Item>
                  )}
                </Menu.Popup>
              </Menu.Positioner>
            </Menu.Portal>
          </Menu.Root>
        ) : (
          <div className={styles.account}>{account}</div>
        )}
      </div>
    </header>
  )
}
