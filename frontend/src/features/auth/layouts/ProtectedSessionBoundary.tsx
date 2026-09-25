import { useEffect, useSyncExternalStore } from 'react'
import { Outlet, useNavigate } from '@tanstack/react-router'
import { Button } from '@base-ui/react/button'

import {
  getSessionSnapshot,
  subscribeSession,
  logout,
} from '../lib/authSession'

import styles from './ProtectedSessionBoundary.module.scss'

export const ProtectedSessionBoundary = () => {
  const { status } = useSyncExternalStore(subscribeSession, getSessionSnapshot)
  const navigate = useNavigate()

  useEffect(() => {
    if (status === 'requires-login' || status === 'logged-out') {
      void navigate({ to: '/login', replace: true })
    }
  }, [status, navigate])

  if (status === 'normal') return <Outlet />

  return (
    <main className={styles.feedback}>
      {status === 'logout-error' ? (
        <>
          <p role="alert">Logout could not be confirmed. Please try again.</p>
          <Button
            onClick={() => {
              void logout()
            }}
          >
            Try again
          </Button>
        </>
      ) : (
        <p role="status">
          {status === 'logging-out' ? 'Logging out…' : 'Returning to login…'}
        </p>
      )}
    </main>
  )
}
