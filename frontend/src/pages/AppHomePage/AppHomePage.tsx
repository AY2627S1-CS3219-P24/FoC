import { useNavigate } from '@tanstack/react-router'
import { Button } from '@base-ui/react/button'

import { useAuth } from '#/features/auth/providers/AuthProvider'
import { RequesterHomePage } from '#/features/orders/pages/RequesterHomePage/RequesterHomePage'

import styles from './AppHomePage.module.scss'

export const AppHomePage = () => {
  const navigate = useNavigate()
  const auth = useAuth()

  const handleLogout = async () => {
    await auth.logout()
    await navigate({ to: '/login', replace: true })
  }

  return (
    <>
      <RequesterHomePage />
      <Button
        className={styles.logout}
        onClick={() => {
          void handleLogout()
        }}
      >
        Log out
      </Button>
    </>
  )
}
