import { Menu } from '@base-ui/react/menu'
import { useNavigate } from '@tanstack/react-router'
import styles from './OrderModeSwitcher.module.scss'

export const OrderModeSwitcher = ({
  mode,
}: {
  mode: 'requester' | 'courier'
}) => {
  const navigate = useNavigate()
  return (
    <Menu.Root>
      <Menu.Trigger
        className={styles.trigger}
        aria-label={`Switch view: ${mode === 'requester' ? 'Requester' : 'Courier'}`}
      >
        {mode === 'requester' ? 'Requester' : 'Courier'}{' '}
        <span aria-hidden="true">⌄</span>
      </Menu.Trigger>
      <Menu.Portal>
        <Menu.Positioner sideOffset={6}>
          <Menu.Popup className={styles.popup}>
            <Menu.Item
              className={styles.item}
              onClick={() => {
                void navigate({ to: '/app' })
              }}
            >
              Requester
            </Menu.Item>
            <Menu.Item
              className={styles.item}
              onClick={() => {
                void navigate({ to: '/courier' })
              }}
            >
              Courier
            </Menu.Item>
          </Menu.Popup>
        </Menu.Positioner>
      </Menu.Portal>
    </Menu.Root>
  )
}
