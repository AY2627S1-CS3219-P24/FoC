import { useNavigate } from '@tanstack/react-router'
import { Button } from '@base-ui/react/button'

import { logout } from '#/features/auth/lib/authSession'

import styles from './AppHomePage.module.scss'

export const AppHomePage = () => {
  const navigate = useNavigate()

  const handleLogout = async () => {
    const result = await logout()

    if (result === 'completed') {
      await navigate({ to: '/login', replace: true })
    }
  }

  return (
    <main className={styles.page}>
      <h1>Welcome to FoC</h1>
      <p>You are signed in.</p>
      <Button
        className={styles.logout}
        onClick={() => {
          void handleLogout()
        }}
      >
        Log out
      </Button>
    </main>
  )
}
