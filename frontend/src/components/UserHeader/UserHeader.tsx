import { Children, cloneElement, isValidElement, useEffect, useId, useState } from 'react'
import { Button } from '@base-ui/react/button'
import { Menu } from '@base-ui/react/menu'
import type { ReactElement, ReactNode } from 'react'
import {
  ChevronDown,
  ClipboardList,
  House,
  Map as MapIcon,
  Menu as MenuIcon,
  X,
} from 'lucide-react'
import styles from './UserHeader.module.scss'

const ITEM_ICONS = {
  Home: House,
  Map: MapIcon,
  'My Errands': ClipboardList,
} as const

const NARROW_HEADER = '(max-width: 700px)'

const useNarrowHeader = () => {
  const [narrow, setNarrow] = useState(
    () => window.matchMedia(NARROW_HEADER).matches,
  )
  useEffect(() => {
    const query = window.matchMedia(NARROW_HEADER)
    const update = () => setNarrow(query.matches)
    update()
    query.addEventListener('change', update)
    return () => query.removeEventListener('change', update)
  }, [])
  return narrow
}

const illustratedNavigation = (navigation: ReactNode) =>
  Children.map(navigation, (child) => {
    if (!isValidElement<{ children?: ReactNode }>(child)) return child
    const label = child.props.children
    if (typeof label !== 'string' || !(label in ITEM_ICONS)) return child
    const Icon = ITEM_ICONS[label as keyof typeof ITEM_ICONS]
    return cloneElement(child as ReactElement<{ children?: ReactNode }>, undefined, (
      <>
        <Icon
          className={styles.navIcon}
          size={20}
          strokeWidth={1.8}
          aria-hidden="true"
        />
        <span>{label}</span>
      </>
    ))
  })

export type UserHeaderProps = {
  navigation: ReactNode
  brand?: ReactNode
  name: string
  avatarUrl?: string | null
  availableCredits?: number | null
  balanceLabel?: string
  hasUnreadNotifications?: boolean
  onNotificationsClick?: () => void
  onAccountClick?: () => void
  onAdminClick?: () => void
  onLogout?: () => void
  overlay?: boolean
}

export const UserHeader = ({
  navigation,
  brand = 'FoC',
  name,
  avatarUrl,
  availableCredits,
  balanceLabel,
  hasUnreadNotifications = false,
  onNotificationsClick,
  onAccountClick,
  onAdminClick,
  onLogout,
  overlay = false,
}: UserHeaderProps) => {
  const narrow = useNarrowHeader()
  const [navOpen, setNavOpen] = useState(false)
  const navPanelId = useId()
  const [failedAvatar, setFailedAvatar] = useState<string | null>(null)
  const balance =
    availableCredits == null
      ? (balanceLabel ?? 'Balance unavailable')
      : `${availableCredits} credits`
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
        <span className={styles.balance}>{balance}</span>
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

  useEffect(() => {
    if (!navOpen) return
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setNavOpen(false)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [navOpen])

  const island = overlay ? styles.island : undefined
  return (
    <header
      className={overlay ? `${styles.header} ${styles.overlay}` : styles.header}
    >
      <div className={[styles.navGroup, island].filter(Boolean).join(' ')}>
        {narrow && (
          <button
            type="button"
            className={styles.menuButton}
            aria-expanded={navOpen}
            aria-controls={navPanelId}
            aria-label={navOpen ? 'Close menu' : 'Open menu'}
            onClick={() => setNavOpen((open) => !open)}
          >
            {navOpen ? (
              <X size={22} strokeWidth={1.8} aria-hidden="true" />
            ) : (
              <MenuIcon size={22} strokeWidth={1.8} aria-hidden="true" />
            )}
          </button>
        )}
        <div className={styles.brand}>{brand}</div>
        <nav
          aria-label="Main navigation"
          hidden={narrow || undefined}
          className={`${styles.navigation} ${styles.barNavigation}`}
        >
          {navigation}
        </nav>
      </div>
      <div className={[styles.actions, island].filter(Boolean).join(' ')}>
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
                  {narrow && <p className={styles.menuBalance}>{balance}</p>}
                  <Menu.Item
                    className={styles.menuItem}
                    onClick={onAccountClick}
                  >
                    Profile
                  </Menu.Item>
                  {onAdminClick && (
                    <Menu.Item className={styles.menuItem} onClick={onAdminClick}>
                      Admin
                    </Menu.Item>
                  )}
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
      {narrow && navOpen && (
        <nav
          id={navPanelId}
          aria-label="Main navigation"
          className={styles.navPanel}
          onClick={() => setNavOpen(false)}
        >
          {illustratedNavigation(navigation)}
        </nav>
      )}
    </header>
  )
}
