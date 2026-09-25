import { axiosClient } from '#/lib/axiosClient'

export const logoutUser = async (): Promise<void> => {
  await axiosClient.post('/auth/logout', undefined, { timeout: 10_000 })
}
