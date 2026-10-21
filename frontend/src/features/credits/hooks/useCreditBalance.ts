import { useQuery } from '@tanstack/react-query'
import { getCreditBalance } from '../api/credits.api'

export const creditKeys = { user: (id?: string) => ['credits', id] as const }
export const useCreditBalance = (userId?: string) =>
  useQuery({
    queryKey: creditKeys.user(userId),
    queryFn: ({ signal }) => getCreditBalance(signal),
    enabled: !!userId,
    staleTime: 0,
    refetchOnMount: 'always',
    refetchOnWindowFocus: 'always',
    refetchInterval: 30_000,
    refetchIntervalInBackground: false,
    retry: false,
  })
