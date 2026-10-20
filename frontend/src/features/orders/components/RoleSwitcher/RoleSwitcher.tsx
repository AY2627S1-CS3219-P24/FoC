import { Link } from '@tanstack/react-router'
import { Menu } from '@base-ui/react'
import { ChevronDown } from 'lucide-react'
import styles from './RoleSwitcher.module.scss'

type RoleSwitcherProps = {
  activeMode: 'requestor' | 'courier'
}

export const RoleSwitcher = ({ activeMode }: RoleSwitcherProps) => {
  const currentLabel = activeMode === 'requestor' ? 'Request' : 'Deliver'
  const alternate =
    activeMode === 'requestor'
      ? { label: 'Deliver', mode: 'courier' as const }
      : { label: 'Request', mode: 'requestor' as const }

  return (
    <Menu.Root>
      <Menu.Trigger className={styles.button}>
        <span className={styles.roleSwitcherLabel}>{currentLabel}</span>
        <ChevronDown size={16} strokeWidth={2} aria-hidden="true" />
      </Menu.Trigger>
      <Menu.Portal>
        <Menu.Positioner className={styles.positioner} sideOffset={8}>
          <Menu.Popup className={styles.popup}>
            <Menu.Item
              className={styles.item}
              render={<Link to="/home" search={{ mode: alternate.mode }} />}
            >
              {alternate.label}
            </Menu.Item>
          </Menu.Popup>
        </Menu.Positioner>
      </Menu.Portal>
    </Menu.Root>
  )
}
