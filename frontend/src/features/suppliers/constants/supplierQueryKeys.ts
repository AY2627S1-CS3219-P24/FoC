export const supplierQueryKeys = {
  all: ['suppliers'] as const,
  list: (includeInactive: boolean) =>
    [...supplierQueryKeys.all, 'list', { includeInactive }] as const,
  detail: (id: string) => [...supplierQueryKeys.all, 'detail', id] as const,
}
