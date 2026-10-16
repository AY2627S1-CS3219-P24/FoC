import { useQuery } from '@tanstack/react-query'
import { listSuppliers } from '../api/suppliers.api'

export const usePickupLocations = (enabled: boolean) =>
  useQuery({
    queryKey: ['suppliers', 'pickup-locations'],
    queryFn: () => listSuppliers({}),
    enabled,
    retry: false,
    select: (suppliers) =>
      suppliers
        .filter((supplier) => supplier.active)
        .map((supplier) => ({
          id: supplier.id,
          name: supplier.name,
          address: [
            supplier.building,
            supplier.floor,
            supplier.locationDescription,
          ]
            .filter(Boolean)
            .join(' · '),
        })),
  })
