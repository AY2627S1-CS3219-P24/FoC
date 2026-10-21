import { Link, useNavigate } from '@tanstack/react-router'
import { UserLayout } from '#/layouts/UserLayout/UserLayout'
import { useUserProfile } from '../hooks/useUserProfile'
import { useSession } from '../hooks/useSession'
import { useAuth } from '../providers/AuthProvider'
import { useCreditBalance } from '#/features/credits/hooks/useCreditBalance'
import { orderErrorCode } from '#/features/orders/utils/orderErrors'

export const AccountLayout = () => {
  const { data } = useUserProfile()
  const { userId } = useSession()
  const balance = useCreditBalance(userId)
  const navigate = useNavigate()
  const auth = useAuth()
  return (
    <UserLayout
      name={data?.name ?? 'Your account'}
      avatarUrl={data?.avatarUrl}
      availableCredits={balance.error ? null : balance.data?.availableBalance}
      balanceLabel={
        balance.isPending
          ? 'Loading credits…'
          : orderErrorCode(balance.error) === 'CREDIT_ACCOUNT_PENDING'
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
          <span aria-disabled="true">Locations</span>
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
      onLogout={() => {
        void auth.logout().then(() => navigate({ to: '/login', replace: true }))
      }}
    />
  )
}
