import { axiosClient } from '#/lib/axiosClient'
import type {
  CreateOrderRequest,
  CreateOrderResponse,
  DeliveryPhoto,
  Errand,
  MineParams,
  OrderAction,
  OrderActionResponse,
  OrderPage,
} from '../types/errand.types'

const timeout = 15_000
export const listAvailableOrders = async (
  params: { q: string; page: number; size: number },
  signal?: AbortSignal,
) =>
  (await axiosClient.get<OrderPage>('/orders', { params, signal, timeout }))
    .data
export const listMyOrders = async (params: MineParams, signal?: AbortSignal) =>
  (
    await axiosClient.get<OrderPage>('/orders/mine', {
      params,
      signal,
      timeout,
    })
  ).data
export const listRequesterOrders = async (
  requesterId: string,
  page: number,
  signal?: AbortSignal,
) =>
  (
    await axiosClient.get<OrderPage>('/orders', {
      params: { requesterId, page, size: 20 },
      signal,
      timeout,
    })
  ).data
export const getOrder = async (id: string, signal?: AbortSignal) =>
  (await axiosClient.get<Errand>(`/orders/${id}`, { signal, timeout })).data
export const createOrder = async (input: CreateOrderRequest) =>
  (await axiosClient.post<CreateOrderResponse>('/orders', input, { timeout }))
    .data
export const retryCreation = async (id: string) =>
  (
    await axiosClient.post<CreateOrderResponse>(
      `/orders/${id}/retry-creation`,
      undefined,
      { timeout },
    )
  ).data
export const actOnOrder = async (id: string, action: OrderAction) =>
  (
    await axiosClient.post<OrderActionResponse>(
      `/orders/${id}/${action === 'pickup' ? 'pick-up' : action}`,
      undefined,
      { timeout },
    )
  ).data
// The courier submits all delivery photos tgt (1 to 5, JPEG & PNG, up to 5 MB each)
export const deliverOrder = async (id: string, photos: Array<File>) => {
  const body = new FormData()
  photos.forEach((photo) => body.append('photos', photo))
  return (
    await axiosClient.post<OrderActionResponse>(`/orders/${id}/deliver`, body, {
      timeout: 120_000,
    })
  ).data
}
export const getDeliveryPhotos = async (id: string, signal?: AbortSignal) =>
  (
    await axiosClient.get<Array<DeliveryPhoto>>(
      `/orders/${id}/delivery-photos`,
      { signal, timeout },
    )
  ).data
