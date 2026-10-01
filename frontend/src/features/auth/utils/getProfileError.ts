import { isAxiosError } from 'axios'

export const getProfileError = (error: unknown) => {
  if (
    isAxiosError<{ message?: string; fieldErrors?: Record<string, string> }>(
      error,
    )
  ) {
    const body = error.response?.data
    return {
      message: body?.message ?? 'Could not save changes. Please try again.',
      fields: body?.fieldErrors ?? {},
    }
  }
  return {
    message: 'Could not save changes. Please try again.',
    fields: {} as Record<string, string>,
  }
}
