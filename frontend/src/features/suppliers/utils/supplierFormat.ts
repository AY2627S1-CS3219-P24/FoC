import { isAxiosError } from 'axios'
import type { Supplier, SupplierCategory } from '../types/supplier.types'

export const categoryLabels: Record<SupplierCategory, string> = {
  FOOD: 'Food',
  COFFEE: 'Coffee',
  SHOPPING: 'Shopping',
  PRINTING: 'Printing',
  OTHER: 'Other',
}

export const formatOpeningHours = (supplier: Supplier) =>
  `${supplier.openingTime.slice(0, 5)} – ${supplier.closingTime.slice(0, 5)}`

export const formatLocation = (supplier: Supplier) =>
  supplier.floor
    ? `${supplier.building}, Level ${supplier.floor}`
    : supplier.building

/**
 * Time is in format "HH:MM".
 * If a closing time earlier than the opening time, then means the supplier closes after midnight, need to check the box
 */
export const isOpenAt = (
  openingTime: string,
  closingTime: string,
  now: Date,
) => {
  const current = `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`
  return closingTime < openingTime
    ? current >= openingTime || current < closingTime
    : current >= openingTime && current < closingTime
}

export const getErrorMessage = (error: unknown) => {
  if (isAxiosError<{ message?: string }>(error)) {
    if (error.response?.status === 401) return 'Please log in again.'
    if (error.response?.status === 403)
      return 'Only admins can manage suppliers.'
    if (error.response?.data.message) return error.response.data.message
  }
  return 'Something went wrong. Please try again.'
}
