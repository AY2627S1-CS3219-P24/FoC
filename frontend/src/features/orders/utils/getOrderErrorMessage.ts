import { isAxiosError } from 'axios'

export const getOrderErrorMessage = (error: unknown) => {
  if (isAxiosError<{ message?: string }>(error)) {
    if (error.response?.data.message) return error.response.data.message
    if (error.response?.status === 403)
      return 'This errand is no longer available to you.'
    if (error.response?.status === 404) return 'Errand not found.'
  }
  return 'Could not complete the request. Please refresh and try again.'
}
