import { Link } from '@tanstack/react-router'
import { Menu } from '@base-ui/react'
import styles from './RoleSwitcher.module.scss'

type RoleSwitcherProps = {
  activeMode: 'requestor' | 'courier'
}

const ChevronDownIcon = () => (
  <svg
    xmlns="http://www.w3.org/2000/svg"
    width="16"
    height="16"
    fill="currentColor"
    className="bi bi-chevron-down"
    viewBox="0 0 16 16"
  >
    <path
      fillRule="evenodd"
      d="M1.646 4.646a.5.5 0 0 1 .708 0L8 10.293l5.646-5.647a.5.5 0 0 1 .708.708l-6 6a.5.5 0 0 1-.708 0l-6-6a.5.5 0 0 1 0-.708"
    />
  </svg>
)

export const RoleSwitcher = ({ activeMode }: RoleSwitcherProps) => {
  const currentLabel = activeMode === 'requestor' ? 'Request' : 'Deliver'
  const alternate =
    activeMode === 'requestor'
      ? { label: 'Deliver', to: '/courier' as const }
      : { label: 'Request', to: '/app' as const }

  return (
    <Menu.Root>
      <Menu.Trigger className={styles.button}>
        <span className={styles.roleSwitcherLabel}>{currentLabel}</span>
        <ChevronDownIcon />
      </Menu.Trigger>
      <Menu.Portal>
        <Menu.Positioner className={styles.positioner} sideOffset={8}>
          <Menu.Popup className={styles.popup}>
            <Menu.Item
              className={styles.item}
              render={<Link to={alternate.to} />}
            >
              {alternate.label}
            </Menu.Item>
          </Menu.Popup>
        </Menu.Positioner>
      </Menu.Portal>
    </Menu.Root>
  )
}
