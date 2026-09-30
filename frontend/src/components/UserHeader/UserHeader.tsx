import { useState } from 'react'
import { Button } from '@base-ui/react/button'
import type { ReactNode } from 'react'
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
        <svg
          className={styles.chevron}
          width="12"
          height="12"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.5"
          aria-hidden="true"
        >
          <path d="m7 10 5 5 5-5" />
        </svg>
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
          <Button
            className={styles.account}
            onClick={onAccountClick}
            aria-label={`Open account for ${name}`}
          >
            {account}
          </Button>
        ) : (
          <div className={styles.account}>{account}</div>
        )}
      </div>
    </header>
  )
}
