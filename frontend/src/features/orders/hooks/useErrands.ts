import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  acceptErrand,
  getErrand,
  listAvailableErrands,
  listCourierErrands,
  pickUpErrand,
} from '../api/errands.api'

export const errandKeys = {
  all: ['errands'] as const,
  available: (page: number) => [...errandKeys.all, 'available', page] as const,
  courier: (page: number) =>
    [...errandKeys.all, 'mine', 'courier', page] as const,
  detail: (id: string | null) => [...errandKeys.all, 'detail', id] as const,
}

export const useAvailableErrands = (page: number) =>
  useQuery({
    queryKey: errandKeys.available(page),
    queryFn: ({ signal }) => listAvailableErrands(page, signal),
    staleTime: 0,
    gcTime: 0,
  })

export const useCourierErrands = (page: number) =>
  useQuery({
    queryKey: errandKeys.courier(page),
    queryFn: ({ signal }) => listCourierErrands(page, signal),
    staleTime: 0,
    gcTime: 0,
  })

export const useErrand = (id: string | null) =>
  useQuery({
    queryKey: errandKeys.detail(id),
    queryFn: ({ signal }) => getErrand(id!, signal),
    enabled: id !== null,
    staleTime: 0,
    gcTime: 0,
    retry: false,
  })

export const useAcceptErrand = () => {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: acceptErrand,
    // A failed response may still follow a successful acceptance on the server.
    onSettled: () =>
      queryClient.invalidateQueries({ queryKey: errandKeys.all }),
  })
}

export const usePickUpErrand = () => {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: pickUpErrand,
    onSettled: () =>
      queryClient.invalidateQueries({ queryKey: errandKeys.all }),
  })
}
