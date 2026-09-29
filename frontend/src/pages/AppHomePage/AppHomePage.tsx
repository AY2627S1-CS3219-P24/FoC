import { useNavigate } from '@tanstack/react-router'
import { Button } from '@base-ui/react/button'

import { useAuth } from '#/features/auth/providers/AuthProvider'

import styles from './AppHomePage.module.scss'

export const AppHomePage = () => {
  const navigate = useNavigate()
  const auth = useAuth()

  const handleLogout = async () => {
    await auth.logout()
    await navigate({ to: '/login', replace: true })
  }

  return (
    <section className={styles.page}>
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
    </section>
  )
}
