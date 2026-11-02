export type OrderListSearch = {
  tab: 'active' | 'history'
  participation: 'all' | 'requested' | 'delivering'
  page: number
}
export const parsePage = (value: unknown) =>
  Number.isSafeInteger(Number(value)) && Number(value) > 0 ? Number(value) : 0
export const parseOrderListSearch = (
  search: Record<string, unknown>,
): OrderListSearch => ({
  tab: search.tab === 'history' ? 'history' : 'active',
  participation:
    search.participation === 'requested' ||
    search.participation === 'delivering'
      ? search.participation
      : 'all',
  page: parsePage(search.page),
})
export const parseAdminOrderSearch = (search: Record<string, unknown>) => ({
  requesterId: typeof search.requesterId === 'string' ? search.requesterId : '',
  page: parsePage(search.page),
})
