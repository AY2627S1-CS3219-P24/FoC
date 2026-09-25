import { useMutation } from '@tanstack/react-query'
import { isAxiosError } from 'axios'

import { registerUser } from '#/features/auth/api/registerUser.api'
import type { RegisterUserRequest } from '#/features/auth/types/auth.types'

const getRegisterErrorMessage = (error: unknown): string | undefined => {
  if (error == null) {
    return undefined
  }

  if (isAxiosError<{ message?: unknown } | null | undefined>(error)) {
    if (!error.response) {
      return 'Unable to reach the server. Please try again.'
    }

    const { status, data } = error.response

    if (
      status === 400 &&
      data?.message === 'User already exists with this email'
    ) {
      return 'An account with this email already exists.'
    }

    if (status === 400) {
      return 'Please check your registration details and try again.'
    }
  }

  return 'Unable to create your account. Please try again.'
}

export const useRegister = () => {
  const mutation = useMutation({
    // Forward only the request, without Query's mutation context.
    mutationFn: (request: RegisterUserRequest) => registerUser(request),

    // After failure, let the user retry explicitly.
    retry: false,

    // Attempt immediately, including while marked offline.
    networkMode: 'always',

    gcTime: 0,
  })

  return {
    ...mutation,
    errorMessage: getRegisterErrorMessage(mutation.error),
  }
}
