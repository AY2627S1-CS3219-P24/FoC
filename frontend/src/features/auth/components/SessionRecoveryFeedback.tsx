import { useRouter } from '@tanstack/react-router'
import { Button } from '@base-ui/react/button'

import styles from './SessionRecoveryFeedback.module.scss'

export const SessionRecoveryPending = () => (
  <main className={styles.container}>
    <p role="status">Restoring your session…</p>
  </main>
)

export const SessionRecoveryError = () => {
  const router = useRouter()

  return (
    <main className={styles.container}>
      <p role="alert">Unable to restore your session. Please try again.</p>
      <Button
        onClick={() => {
          void router.invalidate()
        }}
      >
        Try again
      </Button>
    </main>
  )
}
