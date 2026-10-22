import { useQuery } from '@tanstack/react-query'
import { getCreditBalance } from '../api/creditBalance.api'

export const useCreditBalance = () =>
  useQuery({
    queryKey: ['current-user', 'credit-balance'],
    queryFn: ({ signal }) => getCreditBalance(signal),
    staleTime: 0,
    gcTime: 0,
  })
