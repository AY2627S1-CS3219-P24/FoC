import { Button } from '@base-ui/react/button'

import { logout } from '#/features/auth/lib/authSession'

import styles from './AppHomePage.module.scss'

export const AppHomePage = () => (
  <main className={styles.page}>
    <h1>Welcome to FoC</h1>
    <p>You are signed in.</p>
    <Button
      className={styles.logout}
      onClick={() => {
        void logout()
      }}
    >
      Log out
    </Button>
  </main>
)
