import { useEffect, useRef } from 'react'
import { useMutation } from '@tanstack/react-query'
import { resendVerification, verifyEmail } from '../api/emailVerification.api'

export const useEmailVerification = (token?: string) => {
  const verification = useMutation({
    mutationFn: verifyEmail,
    retry: false,
    networkMode: 'always',
    gcTime: 0,
  })
  const resend = useMutation({
    mutationFn: resendVerification,
    retry: false,
    networkMode: 'always',
    gcTime: 0,
  })
  const startedToken = useRef<string | undefined>(undefined)
  const { mutate: confirm } = verification

  useEffect(() => {
    // A render or StrictMode effect replay must not consume the same link twice.
    if (token && startedToken.current !== token) {
      startedToken.current = token
      confirm(token)
    }
  }, [token, confirm])

  return { verification, resend }
}
