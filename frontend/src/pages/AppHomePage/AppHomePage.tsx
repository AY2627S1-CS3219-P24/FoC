import { Link, useNavigate } from '@tanstack/react-router'
import { Button } from '@base-ui/react/button'

import { useAuth } from '#/features/auth/providers/AuthProvider'
import { UserLayout } from '#/layouts/UserLayout'
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
    <UserLayout
      name="My account"
      brand={<Link to="/app">FoC</Link>}
      navigation={
        <>
          <Link to="/app" aria-current="page">
            Home
          </Link>
          <span
            aria-disabled="true"
            title="Locations page is not connected yet"
          >
            Locations
          </span>
          <span
            aria-disabled="true"
            title="My Errands page is not connected yet"
          >
            My Errands
          </span>
        </>
      }
    >
      <RequesterHomePage />
      <Button
        className={styles.logout}
        onClick={() => {
          void handleLogout()
        }}
      >
        Log out
      </Button>
    </UserLayout>
  )
}
