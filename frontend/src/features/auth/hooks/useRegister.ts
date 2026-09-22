import { useMutation } from '@tanstack/react-query'
import { isAxiosError } from 'axios'
import { registerUser } from '#/features/auth/api/registerUser.api'
import type { RegisterUserRequest } from '#/features/auth/types/auth.types'

/**
 * Maps registration failures to user-facing messages.
 * Unrecognized server details are not displayed directly.
 */
const getRegisterErrorMessage = (error: unknown): string | undefined => {
  if (error == null) {
    return undefined
  }

  if (isAxiosError<{ message?: unknown } | null | undefined>(error)) {
    if (!error.response) {
      return 'Unable to reach the server. Please try again.'
    }

    const { status, data } = error.response

    // The current backend identifies duplicate emails with this message.
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

/**
 * Manages the registration request lifecycle and its error message.
 * Navigation remains the responsibility of the calling page.
 */
export const useRegister = () => {
  const mutation = useMutation({
    // Forward only the request, without Query's mutation context.
    mutationFn: (request: RegisterUserRequest) => registerUser(request),

    // Attempt immediately, including while marked offline.
    // After failure, let the user retry explicitly.
    retry: false,
    networkMode: 'always',

    // Remove inactive mutation entries promptly.
    // This does not guarantee that passwords are erased from memory.
    gcTime: 0,
  })

  return {
    ...mutation,
    errorMessage: getRegisterErrorMessage(mutation.error),
  }
}
