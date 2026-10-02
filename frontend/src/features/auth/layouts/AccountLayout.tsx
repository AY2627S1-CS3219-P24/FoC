import { Link, useNavigate } from '@tanstack/react-router'
import { UserLayout } from '#/layouts/UserLayout/UserLayout'
import { useUserProfile } from '../hooks/useUserProfile'
import { useAuth } from '../providers/AuthProvider'

export const AccountLayout = () => {
  const { data } = useUserProfile()
  const navigate = useNavigate()
  const auth = useAuth()
  return (
    <UserLayout
      name={data?.name ?? 'Your account'}
      avatarUrl={data?.avatarUrl}
      brand={<Link to="/app">FoC</Link>}
      navigation={
        <>
          <Link to="/app">Home</Link>
          <span aria-disabled="true">Locations</span>
          <span aria-disabled="true">My Errands</span>
        </>
      }
      onAccountClick={() => {
        void navigate({ to: '/profile' })
      }}
      onLogout={() => {
        void auth.logout().then(() => navigate({ to: '/login', replace: true }))
      }}
    />
  )
}
