import { useMutation } from '@tanstack/react-query'
import { isAxiosError } from 'axios'

import { loginUser } from '../api/loginUser.api'
import type { LoginRequest } from '../types/auth.types'

const getLoginErrorMessage = (error: unknown): string | undefined => {
  if (error == null) {
    return undefined
  }

  if (isAxiosError(error)) {
    if (!error.response) {
      return 'Unable to reach the server. Please try again.'
    }
    if (error.response.status === 401) {
      return 'Incorrect email or password.'
    }
    if (error.response.status === 400) {
      return 'Please check your login details and try again.'
    }
  }

  return 'Unable to log in. Please try again.'
}

export const useLogin = () => {
  const mutation = useMutation({
    mutationFn: (request: LoginRequest) => loginUser(request),
    retry: false,
    networkMode: 'always',
    gcTime: 0,
  })

  return { ...mutation, errorMessage: getLoginErrorMessage(mutation.error) }
}
