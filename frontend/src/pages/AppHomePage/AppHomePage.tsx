import { useNavigate } from '@tanstack/react-router'
import { Button } from '@base-ui/react/button'
import { useState } from 'react'

import { useAuth } from '#/features/auth/providers/AuthProvider'

import styles from './AppHomePage.module.scss'

export const AppHomePage = () => {
  const navigate = useNavigate()
  const auth = useAuth()
  const [logoutError, setLogoutError] = useState<string | null>(null)

  const handleLogout = async () => {
    setLogoutError(null)
    try {
      await auth.logout()
    } catch {
      setLogoutError('Unable to log out. Please try again.')
      return
    }
    await navigate({ to: '/login', replace: true })
  }

  return (
    <main className={styles.page}>
      <h1>Welcome to FoC</h1>
      <p>You are signed in.</p>
      {logoutError && (
        <p className={styles.error} role="alert">
          {logoutError}
        </p>
      )}
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
