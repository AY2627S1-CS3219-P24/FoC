import {
  previewOrders,
  addPreviewOrder,
  updatePreviewOrder,
} from '../mocks/orders.mock'
import type { CreateErrandValues } from '../schemas/createErrand.schema'
import type { OrderAction, OrderUser } from '../types/errand.types'

// Temporary UI preview. Replace these functions with agreed backend requests.
export const ordersPreviewEnabled = import.meta.env.DEV
const requirePreview = () => {
  if (!ordersPreviewEnabled)
    throw new Error('Order service integration is not available yet.')
}
export const listOrders = async (user: OrderUser) => {
  requirePreview()
  return structuredClone(previewOrders(user))
}
export const getOrder = async (user: OrderUser, id: string) => {
  requirePreview()
  const order = previewOrders(user).find((item) => item.id === id)
  if (!order) throw new Error('This errand could not be found.')
  return structuredClone(order)
}
export const createOrder = async (
  user: OrderUser,
  input: CreateErrandValues,
) => {
  requirePreview()
  return addPreviewOrder(user, input)
}
export const actOnOrder = async (
  user: OrderUser,
  id: string,
  action: OrderAction,
) => {
  requirePreview()
  return updatePreviewOrder(user, id, action)
}
