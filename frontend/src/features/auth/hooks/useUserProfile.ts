import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  changePassword,
  getAvatar,
  getUserProfile,
  removeAvatar,
  updateUserProfile,
  uploadAvatar,
} from '../api/userProfile.api'
import type { UserProfileDto } from '../types/auth.types'

export const profileKey = ['current-user', 'profile'] as const

export const useUserProfile = () =>
  useQuery({
    queryKey: profileKey,
    queryFn: ({ signal }) => getUserProfile(signal),
    retry: false,
  })

export const useProfileActions = () => {
  const client = useQueryClient()
  const saved = (profile: UserProfileDto) =>
    client.setQueryData(profileKey, profile)
  const update = useMutation({
    mutationFn: updateUserProfile,
    onSuccess: saved,
    retry: false,
    networkMode: 'always',
    gcTime: 0,
  })
  const password = useMutation({
    mutationFn: changePassword,
    retry: false,
    networkMode: 'always',
    gcTime: 0,
  })
  const upload = useMutation({
    mutationFn: uploadAvatar,
    onSuccess: saved,
    retry: false,
    networkMode: 'always',
    gcTime: 0,
  })
  const remove = useMutation({
    mutationFn: removeAvatar,
    retry: false,
    networkMode: 'always',
    onSuccess: () => {
      client.setQueryData<UserProfileDto>(profileKey, (profile) =>
        profile ? { ...profile, avatarUrl: null } : profile,
      )
    },
  })
  return { update, password, upload, remove }
}

// Avatar endpoints require a Bearer token, so an ordinary image URL cannot be used.
export const useProfileAvatar = (version?: string | null) => {
  const { data } = useQuery({
    queryKey: ['current-user', 'avatar', version],
    queryFn: ({ signal }) => getAvatar(signal),
    enabled: Boolean(version),
    retry: false,
    networkMode: 'always',
    gcTime: 0,
  })
  const [image, setImage] = useState<{ blob: Blob; url: string } | null>(null)
  useEffect(() => {
    if (!data) return
    const url = URL.createObjectURL(data)
    setImage({ blob: data, url })
    return () => URL.revokeObjectURL(url)
  }, [data])
  return version && image?.blob === data ? image?.url : undefined
}
