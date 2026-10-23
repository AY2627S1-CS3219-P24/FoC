import { Link, useNavigate, useRouterState } from '@tanstack/react-router'
import { UserLayout } from '#/layouts/UserLayout/UserLayout'
import { useUserProfile } from '../hooks/useUserProfile'
import { useSession } from '../hooks/useSession'
import { useAuth } from '../providers/AuthProvider'
import { useCreditBalance } from '#/features/credits/hooks/useCreditBalance'
import { getApiErrorCode } from '#/lib/httpErrors'

export const AccountLayout = () => {
  const { data } = useUserProfile()
  const { userId, roles } = useSession()
  const balance = useCreditBalance(userId)
  const navigate = useNavigate()
  const auth = useAuth()
  const onMap = useRouterState({
    select: (state) => state.location.pathname === '/map',
  })
  return (
    <UserLayout
      overlay={onMap}
      name={data?.name ?? 'Your account'}
      avatarUrl={data?.avatarUrl}
      availableCredits={balance.error ? null : balance.data?.availableBalance}
      balanceLabel={
        balance.isPending
          ? 'Loading credits…'
          : getApiErrorCode(balance.error) === 'CREDIT_ACCOUNT_PENDING'
            ? 'Credit account preparing'
            : 'Balance unavailable'
      }
      brand={<Link to="/home">FoC</Link>}
      navigation={
        <>
          <Link
            to="/home"
            activeOptions={{ exact: false, includeSearch: false }}
          >
            Home
          </Link>
          <Link to="/map">Map</Link>
          <Link
            to="/errands"
            search={{ tab: 'active', participation: 'all', page: 0 }}
          >
            My Errands
          </Link>
        </>
      }
      onAccountClick={() => {
        void navigate({ to: '/profile' })
      }}
      onAdminClick={
        roles.includes('ADMIN')
          ? () => {
              void navigate({ to: '/admin' })
            }
          : undefined
      }
      onLogout={() => {
        void auth.logout().then(() => navigate({ to: '/login', replace: true }))
      }}
    />
  )
}
